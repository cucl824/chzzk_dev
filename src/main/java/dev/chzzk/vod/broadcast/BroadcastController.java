package dev.chzzk.vod.broadcast;

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
 * 녹화 방송 API. 목록·상세·시청(조회수)은 누구나, 등록은 로그인 사용자, 수정·삭제는 소유자만 가능하다.
 * 로그인 여부는 SecurityConfig 가, 소유자 여부는 BroadcastService 가 서버에서 검사한다.
 */
@RestController
@RequestMapping("/api/broadcasts")
public class BroadcastController {

	private final BroadcastService service;

	public BroadcastController(BroadcastService service) {
		this.service = service;
	}

	public record UpdateRequest(String title, String description) {
	}

	@GetMapping
	public Map<String, List<BroadcastResponse>> list() {
		return Map.of("broadcasts", service.list().stream().map(BroadcastResponse::from).toList());
	}

	@GetMapping("/{id}")
	public Map<String, BroadcastResponse> get(@PathVariable String id) {
		return Map.of("broadcast", BroadcastResponse.from(service.get(id)));
	}

	/**
	 * 등록 (multipart/form-data): title(필수), description(선택),
	 * video(파일 업로드) 또는 videoUrl(저장된 영상 URL 연결) 중 하나.
	 */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, BroadcastResponse> create(@AuthenticationPrincipal LoginUser me,
			@RequestParam(required = false) String title,
			@RequestParam(required = false) String description,
			@RequestParam(required = false) MultipartFile video,
			@RequestParam(required = false) String videoUrl) {
		return Map.of("broadcast", BroadcastResponse.from(service.create(me, title, description, video, videoUrl)));
	}

	@PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, BroadcastResponse> update(@AuthenticationPrincipal LoginUser me, @PathVariable String id,
			@RequestBody UpdateRequest body) {
		return Map.of("broadcast", BroadcastResponse.from(service.update(me, id, body.title(), body.description())));
	}

	@DeleteMapping("/{id}")
	public Map<String, Boolean> delete(@AuthenticationPrincipal LoginUser me, @PathVariable String id) {
		service.delete(me, id);
		return Map.of("ok", true);
	}

	@PostMapping("/{id}/views")
	public Map<String, Long> view(@PathVariable String id) {
		return Map.of("viewCount", service.incrementView(id));
	}

}
