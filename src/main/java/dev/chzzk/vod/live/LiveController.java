package dev.chzzk.vod.live;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 라이브 방송 API. 로그인 없이 누구나 방송을 시작하고 시청할 수 있다.
 * 종료·녹화본 등록은 방송을 시작한 화면이 받은 송출 토큰(X-Publisher-Token)으로만 가능하다.
 */
@RestController
@RequestMapping("/api/lives")
public class LiveController {

	static final String TOKEN_HEADER = "X-Publisher-Token";

	private final LiveService service;

	public LiveController(LiveService service) {
		this.service = service;
	}

	public record StartRequest(String title) {
	}

	public record StartResponse(LiveResponse live, String publisherToken) {
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public StartResponse start(@RequestBody StartRequest request) {
		LiveService.Started started = service.start(request.title());
		return new StartResponse(LiveResponse.from(started.live(), 0), started.publisherToken());
	}

	/** 라이브 목록 (최신순). status=live 또는 ended 로 거를 수 있다. */
	@GetMapping
	public Map<String, List<LiveResponse>> list(@RequestParam(required = false) String status) {
		List<LiveResponse> lives = service.list()
			.stream()
			.filter(live -> status == null || live.getStatus().name().equalsIgnoreCase(status))
			.map(this::toResponse)
			.toList();
		return Map.of("lives", lives);
	}

	@GetMapping("/{id}")
	public Map<String, LiveResponse> get(@PathVariable String id) {
		return Map.of("live", toResponse(service.get(id)));
	}

	@PostMapping("/{id}/end")
	public Map<String, LiveResponse> end(@PathVariable String id,
			@RequestHeader(name = TOKEN_HEADER, required = false) String token) {
		return Map.of("live", toResponse(service.end(id, token)));
	}

	@PostMapping(path = "/{id}/recording", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, LiveResponse> recording(@PathVariable String id,
			@RequestHeader(name = TOKEN_HEADER, required = false) String token,
			@RequestParam(required = false) MultipartFile video) {
		return Map.of("live", toResponse(service.attachRecording(id, token, video)));
	}

	private LiveResponse toResponse(LiveStream live) {
		return LiveResponse.from(live, service.viewerCount(live.getId()));
	}

}
