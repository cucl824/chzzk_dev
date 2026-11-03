package dev.chzzk.vod.live;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import dev.chzzk.vod.broadcast.Broadcast;
import dev.chzzk.vod.broadcast.BroadcastService;
import dev.chzzk.vod.common.ApiException;
import dev.chzzk.vod.user.LoginUser;
import dev.chzzk.vod.user.User;
import dev.chzzk.vod.user.UserService;

/**
 * 라이브 방송. 시작은 로그인 사용자, 종료·제목 수정·삭제·녹화본 등록·송출 연결은 방송 소유자만 할 수 있다.
 * 비로그인은 401, 다른 사용자는 403 으로 서버에서 거절한다.
 */
@Service
public class LiveService {

	private static final Logger log = LoggerFactory.getLogger(LiveService.class);
	private static final DateTimeFormatter STARTED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
		.withZone(ZoneId.systemDefault());

	private final LiveStreamRepository repository;
	private final LiveRooms rooms;
	private final BroadcastService broadcastService;
	private final UserService userService;
	private final ApplicationEventPublisher events;
	private final SecureRandom random = new SecureRandom();

	public LiveService(LiveStreamRepository repository, LiveRooms rooms, BroadcastService broadcastService,
			UserService userService, ApplicationEventPublisher events) {
		this.repository = repository;
		this.rooms = rooms;
		this.broadcastService = broadcastService;
		this.userService = userService;
		this.events = events;
	}

	public record Started(LiveStream live, String publisherToken) {
	}

	/** 라이브 기록이 삭제되면 댓글을 정리하도록 알린다. */
	public record Deleted(String liveId) {
	}

	@Transactional
	public Started start(LoginUser me, String rawTitle) {
		User owner = userService.require(me);
		String title = validTitle(rawTitle);
		byte[] bytes = new byte[24];
		random.nextBytes(bytes);
		String token = HexFormat.of().formatHex(bytes);
		LiveStream live = repository.save(new LiveStream(owner, title, token));
		rooms.open(live.getId());
		return new Started(live, token);
	}

	@Transactional(readOnly = true)
	public List<LiveStream> list() {
		return repository.findAllByOrderByStartedAtDesc();
	}

	@Transactional(readOnly = true)
	public LiveStream get(String id) {
		return repository.findById(id).orElseThrow(() -> ApiException.notFound("라이브 방송을 찾을 수 없습니다."));
	}

	public int viewerCount(String id) {
		return rooms.viewerCount(id);
	}

	/** WebSocket 송출 연결 허용 여부: 방송 중이고, 접속한 로그인 사용자가 소유자이며, 시작한 송출 화면의 토큰이 맞아야 한다. */
	@Transactional(readOnly = true)
	public boolean isPublisher(String id, Long userId, String token) {
		return userId != null && repository.findById(id)
			.filter(LiveStream::isLive)
			.filter(live -> live.isOwnedBy(userId))
			.filter(live -> tokenMatches(live, token))
			.isPresent();
	}

	/** 소유자가 방송을 종료한다. 송출 화면이 아닌 다른 기기·탭에서도 소유자라면 종료할 수 있다. */
	@Transactional
	public LiveStream end(LoginUser me, String id) {
		LiveStream live = requireOwner(me, id);
		live.end();
		rooms.close(id);
		return live;
	}

	@Transactional
	public LiveStream rename(LoginUser me, String id, String rawTitle) {
		LiveStream live = requireOwner(me, id);
		live.rename(validTitle(rawTitle));
		return live;
	}

	/** 종료된 라이브 기록을 소유자가 삭제한다. (연결된 다시보기는 남는다) */
	@Transactional
	public void delete(LoginUser me, String id) {
		LiveStream live = requireOwner(me, id);
		if (live.isLive()) {
			throw new ApiException(HttpStatus.CONFLICT, "방송 중에는 삭제할 수 없습니다. 먼저 방송을 종료하세요.");
		}
		repository.delete(live);
		events.publishEvent(new Deleted(id));
	}

	/** 종료된 방송의 녹화본을 소유자의 다시보기로 등록하고 라이브와 연결한다. */
	@Transactional
	public LiveStream attachRecording(LoginUser me, String id, MultipartFile video) {
		LiveStream live = requireOwner(me, id);
		if (live.isLive()) {
			throw new ApiException(HttpStatus.CONFLICT, "방송을 종료한 뒤 녹화본을 올릴 수 있습니다.");
		}
		if (live.getVodBroadcastId() != null) {
			throw new ApiException(HttpStatus.CONFLICT, "이미 녹화본이 등록된 방송입니다.");
		}
		String title = live.getTitle();
		if (title.length() > Broadcast.TITLE_MAX) {
			title = title.substring(0, Broadcast.TITLE_MAX);
		}
		Broadcast vod = broadcastService.create(me, title,
				"라이브 다시보기 · " + STARTED_AT.format(live.getStartedAt()) + " 방송", video, null);
		live.linkVod(vod.getId());
		return repository.save(live);
	}

	/** 송출자가 끊긴 채 유예 시간이 지나면 자동 종료한다. */
	@EventListener
	@Transactional
	public void onPublisherTimedOut(PublisherTimedOutEvent event) {
		repository.findById(event.liveId()).ifPresent(LiveStream::end);
		rooms.close(event.liveId());
	}

	/** 연결된 다시보기가 삭제되면 라이브의 다시보기 링크를 지운다. */
	@EventListener
	@Transactional
	public void onBroadcastDeleted(BroadcastService.Deleted event) {
		repository.findAllByVodBroadcastId(event.broadcastId()).forEach(live -> live.linkVod(null));
	}

	/** 서버가 재시작되면 메모리의 송출 연결이 모두 사라지므로, 남아 있는 "방송 중" 상태를 종료로 정리한다. */
	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void endStaleLives() {
		List<LiveStream> stale = repository.findAllByStatus(LiveStatus.LIVE);
		stale.forEach(LiveStream::end);
		if (!stale.isEmpty()) {
			log.info("재시작 전에 방송 중이던 라이브 {}개를 종료 상태로 바꿨습니다.", stale.size());
		}
	}

	/** 비로그인 → 401, 다른 사용자(또는 소유자 없는 이전 단계 기록) → 403 */
	private LiveStream requireOwner(LoginUser me, String id) {
		if (me == null) {
			throw ApiException.unauthorized();
		}
		LiveStream live = get(id);
		if (!live.isOwnedBy(me.getId())) {
			throw ApiException.forbidden("이 방송의 소유자만 송출을 제어할 수 있습니다.");
		}
		return live;
	}

	private static String validTitle(String rawTitle) {
		String title = rawTitle == null ? "" : rawTitle.trim();
		if (title.isEmpty()) {
			throw ApiException.badRequest("방송 제목을 입력하세요.");
		}
		if (title.length() > LiveStream.TITLE_MAX) {
			throw ApiException.badRequest("방송 제목은 " + LiveStream.TITLE_MAX + "자 이하로 입력하세요.");
		}
		return title;
	}

	private static boolean tokenMatches(LiveStream live, String token) {
		return token != null && MessageDigest.isEqual(live.getPublisherToken().getBytes(StandardCharsets.UTF_8),
				token.getBytes(StandardCharsets.UTF_8));
	}

}
