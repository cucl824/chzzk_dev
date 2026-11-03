package dev.chzzk.vod.broadcast;

import java.net.URI;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import dev.chzzk.vod.common.ApiException;
import dev.chzzk.vod.storage.VideoStorage;

@Service
public class BroadcastService {

	private final BroadcastRepository repository;
	private final VideoStorage storage;

	public BroadcastService(BroadcastRepository repository, VideoStorage storage) {
		this.repository = repository;
		this.storage = storage;
	}

	@Transactional(readOnly = true)
	public List<Broadcast> list() {
		return repository.findAllByOrderByCreatedAtDesc();
	}

	@Transactional(readOnly = true)
	public Broadcast get(String id) {
		return repository.findById(id).orElseThrow(() -> ApiException.notFound("방송을 찾을 수 없습니다."));
	}

	/** 영상 파일 업로드 또는 영상 URL 연결 중 하나로 녹화 방송을 등록한다. */
	public Broadcast create(String rawTitle, String rawDescription, MultipartFile video, String rawVideoUrl) {
		String title = trim(rawTitle);
		String description = trim(rawDescription);
		String videoUrl = trim(rawVideoUrl);
		boolean hasFile = video != null && !video.isEmpty();

		if (title.isEmpty()) {
			throw ApiException.badRequest("제목을 입력하세요.");
		}
		if (title.length() > Broadcast.TITLE_MAX) {
			throw ApiException.badRequest("제목은 " + Broadcast.TITLE_MAX + "자 이하로 입력하세요.");
		}
		if (description.length() > Broadcast.DESCRIPTION_MAX) {
			throw ApiException.badRequest("설명은 " + Broadcast.DESCRIPTION_MAX + "자 이하로 입력하세요.");
		}
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
			return repository.save(Broadcast.ofUrl(title, description, videoUrl));
		}

		String contentType = video.getContentType();
		if (contentType == null || !contentType.startsWith("video/")) {
			throw ApiException.badRequest("영상 파일(video/*)만 업로드할 수 있습니다.");
		}
		String storedPath = storage.save(video);
		try {
			return repository.save(Broadcast.ofUpload(title, description, storedPath, video.getOriginalFilename(),
					contentType, video.getSize()));
		}
		catch (RuntimeException e) {
			storage.deleteQuietly(storedPath);
			throw e;
		}
	}

	@Transactional
	public long incrementView(String id) {
		if (repository.incrementViewCount(id) == 0) {
			throw ApiException.notFound("방송을 찾을 수 없습니다.");
		}
		return get(id).getViewCount();
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
