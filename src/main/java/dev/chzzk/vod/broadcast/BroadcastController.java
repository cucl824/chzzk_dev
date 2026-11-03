package dev.chzzk.vod.broadcast;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 녹화 방송 API. 이번 단계는 로그인 없이 누구나 등록·조회할 수 있다. (인증 없음) */
@RestController
@RequestMapping("/api/broadcasts")
public class BroadcastController {

	private final BroadcastService service;

	public BroadcastController(BroadcastService service) {
		this.service = service;
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
	public Map<String, BroadcastResponse> create(@RequestParam(required = false) String title,
			@RequestParam(required = false) String description,
			@RequestParam(required = false) MultipartFile video,
			@RequestParam(required = false) String videoUrl) {
		return Map.of("broadcast", BroadcastResponse.from(service.create(title, description, video, videoUrl)));
	}

	@PostMapping("/{id}/views")
	public Map<String, Long> view(@PathVariable String id) {
		return Map.of("viewCount", service.incrementView(id));
	}

}
