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
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import dev.chzzk.vod.broadcast.Broadcast;
import dev.chzzk.vod.broadcast.BroadcastService;
import dev.chzzk.vod.common.ApiException;

@Service
public class LiveService {

	private static final Logger log = LoggerFactory.getLogger(LiveService.class);
	private static final DateTimeFormatter STARTED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
		.withZone(ZoneId.systemDefault());

	private final LiveStreamRepository repository;
	private final LiveRooms rooms;
	private final BroadcastService broadcastService;
	private final SecureRandom random = new SecureRandom();

	public LiveService(LiveStreamRepository repository, LiveRooms rooms, BroadcastService broadcastService) {
		this.repository = repository;
		this.rooms = rooms;
		this.broadcastService = broadcastService;
	}

	public record Started(LiveStream live, String publisherToken) {
	}

	@Transactional
	public Started start(String rawTitle) {
		String title = rawTitle == null ? "" : rawTitle.trim();
		if (title.isEmpty()) {
			throw ApiException.badRequest("방송 제목을 입력하세요.");
		}
		if (title.length() > LiveStream.TITLE_MAX) {
			throw ApiException.badRequest("방송 제목은 " + LiveStream.TITLE_MAX + "자 이하로 입력하세요.");
		}
		byte[] bytes = new byte[24];
		random.nextBytes(bytes);
		String token = HexFormat.of().formatHex(bytes);
		LiveStream live = repository.save(new LiveStream(title, token));
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

	/** 방송 중이고 송출 토큰이 맞는지 (WebSocket 송출자 연결용) */
	@Transactional(readOnly = true)
	public boolean isPublisher(String id, String token) {
		return repository.findById(id).filter(LiveStream::isLive).filter(live -> tokenMatches(live, token)).isPresent();
	}

	/** 송출자가 방송을 종료한다. 이미 종료된 방송이면 그대로 돌려준다. */
	@Transactional
	public LiveStream end(String id, String token) {
		LiveStream live = get(id);
		requirePublisher(live, token);
		live.end();
		rooms.close(id);
		return live;
	}

	/** 종료된 방송의 녹화본을 다시보기로 등록하고 라이브와 연결한다. */
	@Transactional
	public LiveStream attachRecording(String id, String token, MultipartFile video) {
		LiveStream live = get(id);
		requirePublisher(live, token);
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
		Broadcast vod = broadcastService.create(title,
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

	private static void requirePublisher(LiveStream live, String token) {
		if (!tokenMatches(live, token)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "이 방송을 시작한 송출 화면에서만 할 수 있습니다.");
		}
	}

	private static boolean tokenMatches(LiveStream live, String token) {
		return token != null && MessageDigest.isEqual(live.getPublisherToken().getBytes(StandardCharsets.UTF_8),
				token.getBytes(StandardCharsets.UTF_8));
	}

}
