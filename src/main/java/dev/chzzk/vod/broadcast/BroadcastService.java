package dev.chzzk.vod.broadcast;

import java.net.URI;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import dev.chzzk.vod.common.ApiException;
import dev.chzzk.vod.storage.VideoStorage;
import dev.chzzk.vod.user.LoginUser;
import dev.chzzk.vod.user.User;
import dev.chzzk.vod.user.UserService;

@Service
public class BroadcastService {

	private final BroadcastRepository repository;
	private final VideoStorage storage;
	private final UserService userService;
	private final ApplicationEventPublisher events;

	public BroadcastService(BroadcastRepository repository, VideoStorage storage, UserService userService,
			ApplicationEventPublisher events) {
		this.repository = repository;
		this.storage = storage;
		this.userService = userService;
		this.events = events;
	}

	/** 방송이 삭제되면 댓글·라이브 연결을 정리하도록 알린다. */
	public record Deleted(String broadcastId) {
	}

	@Transactional(readOnly = true)
	public List<Broadcast> list() {
		return repository.findAllByOrderByCreatedAtDesc();
	}

	@Transactional(readOnly = true)
	public Broadcast get(String id) {
		return repository.findById(id).orElseThrow(() -> ApiException.notFound("방송을 찾을 수 없습니다."));
	}

	/** 로그인한 사용자가 영상 파일 업로드 또는 영상 URL 연결 중 하나로 녹화 방송을 등록한다. 등록자가 소유자가 된다. */
	@Transactional
	public Broadcast create(LoginUser me, String rawTitle, String rawDescription, MultipartFile video,
			String rawVideoUrl) {
		User owner = userService.require(me);
		String title = trim(rawTitle);
		String description = trim(rawDescription);
		String videoUrl = trim(rawVideoUrl);
		boolean hasFile = video != null && !video.isEmpty();

		validate(title, description);
		if (hasFile && !videoUrl.isEmpty()) {
			throw ApiException.badRequest("영상 파일과 영상 URL 중 하나만 입력하세요.");
		}
		if (!hasFile && videoUrl.isEmpty()) {
			throw ApiException.badRequest("영상 파일을 올리거나 영상 URL을 입력하세요.");
		}

		if (!hasFile) {
			if (!isHttpUrl(videoUrl)) {
				throw ApiException.badRequest("영상 URL은 http(s):// 로 시작해야 합니다.");
			}
			return repository.save(Broadcast.ofUrl(owner, title, description, videoUrl));
		}

		String contentType = video.getContentType();
		if (contentType == null || !contentType.startsWith("video/")) {
			throw ApiException.badRequest("영상 파일(video/*)만 업로드할 수 있습니다.");
		}
		String storedPath = storage.save(video);
		try {
			return repository.save(Broadcast.ofUpload(owner, title, description, storedPath,
					video.getOriginalFilename(), contentType, video.getSize()));
		}
		catch (RuntimeException e) {
			storage.deleteQuietly(storedPath);
			throw e;
		}
	}

	/** 소유자만 제목·설명을 수정한다. */
	@Transactional
	public Broadcast update(LoginUser me, String id, String rawTitle, String rawDescription) {
		Broadcast broadcast = requireOwner(me, id);
		String title = trim(rawTitle);
		String description = trim(rawDescription);
		validate(title, description);
		broadcast.update(title, description);
		return broadcast;
	}

	/** 소유자만 삭제한다. 업로드한 영상 파일과 댓글도 함께 지운다. */
	@Transactional
	public void delete(LoginUser me, String id) {
		Broadcast broadcast = requireOwner(me, id);
		repository.delete(broadcast);
		events.publishEvent(new Deleted(id));
		if (broadcast.getSourceType() == SourceType.UPLOAD) {
			storage.deleteQuietly(broadcast.getVideoUrl());
		}
	}

	@Transactional
	public long incrementView(String id) {
		if (repository.incrementViewCount(id) == 0) {
			throw ApiException.notFound("방송을 찾을 수 없습니다.");
		}
		return get(id).getViewCount();
	}

	/** 비로그인 → 401, 다른 사용자(또는 소유자 없는 이전 단계 방송) → 403 */
	private Broadcast requireOwner(LoginUser me, String id) {
		if (me == null) {
			throw ApiException.unauthorized();
		}
		Broadcast broadcast = get(id);
		if (!broadcast.isOwnedBy(me.getId())) {
			throw ApiException.forbidden("이 방송의 소유자만 수정·삭제할 수 있습니다.");
		}
		return broadcast;
	}

	private static void validate(String title, String description) {
		if (title.isEmpty()) {
			throw ApiException.badRequest("제목을 입력하세요.");
		}
		if (title.length() > Broadcast.TITLE_MAX) {
			throw ApiException.badRequest("제목은 " + Broadcast.TITLE_MAX + "자 이하로 입력하세요.");
		}
		if (description.length() > Broadcast.DESCRIPTION_MAX) {
			throw ApiException.badRequest("설명은 " + Broadcast.DESCRIPTION_MAX + "자 이하로 입력하세요.");
		}
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}

	private static boolean isHttpUrl(String value) {
		try {
			URI uri = URI.create(value);
			String scheme = uri.getScheme();
			return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && uri.getHost() != null;
		}
		catch (IllegalArgumentException e) {
			return false;
		}
	}

}
