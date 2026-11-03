package dev.chzzk.vod.live;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import dev.chzzk.vod.user.LoginUser;

/**
 * 라이브 방송 API. 목록·상세·시청은 누구나, 시작은 로그인 사용자, 종료·수정·삭제·녹화본 등록은 소유자만 가능하다.
 */
@RestController
@RequestMapping("/api/lives")
public class LiveController {

	private final LiveService service;

	public LiveController(LiveService service) {
		this.service = service;
	}

	public record TitleRequest(String title) {
	}

	public record StartResponse(LiveResponse live, String publisherToken) {
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public StartResponse start(@AuthenticationPrincipal LoginUser me, @RequestBody TitleRequest request) {
		LiveService.Started started = service.start(me, request.title());
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

	@PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, LiveResponse> rename(@AuthenticationPrincipal LoginUser me, @PathVariable String id,
			@RequestBody TitleRequest request) {
		return Map.of("live", toResponse(service.rename(me, id, request.title())));
	}

	@DeleteMapping("/{id}")
	public Map<String, Boolean> delete(@AuthenticationPrincipal LoginUser me, @PathVariable String id) {
		service.delete(me, id);
		return Map.of("ok", true);
	}

	@PostMapping("/{id}/end")
	public Map<String, LiveResponse> end(@AuthenticationPrincipal LoginUser me, @PathVariable String id) {
		return Map.of("live", toResponse(service.end(me, id)));
	}

	@PostMapping(path = "/{id}/recording", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, LiveResponse> recording(@AuthenticationPrincipal LoginUser me, @PathVariable String id,
			@RequestParam(required = false) MultipartFile video) {
		return Map.of("live", toResponse(service.attachRecording(me, id, video)));
	}

	private LiveResponse toResponse(LiveStream live) {
		return LiveResponse.from(live, service.viewerCount(live.getId()));
	}

}
