package dev.chzzk.vod.comment;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.chzzk.vod.user.LoginUser;

/** 방송별 댓글 API. 녹화 방송과 라이브 모두 같은 형식이다. */
@RestController
public class CommentController {

	private final CommentService service;

	public CommentController(CommentService service) {
		this.service = service;
	}

	public record ContentRequest(String content) {
	}

	@GetMapping("/api/broadcasts/{id}/comments")
	public Map<String, List<CommentResponse>> broadcastComments(@PathVariable String id) {
		return list(CommentTarget.BROADCAST, id);
	}

	@PostMapping(path = "/api/broadcasts/{id}/comments", consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, CommentResponse> writeBroadcastComment(@AuthenticationPrincipal LoginUser me,
			@PathVariable String id, @RequestBody ContentRequest body) {
		return Map.of("comment", CommentResponse.from(service.create(me, CommentTarget.BROADCAST, id, body.content())));
	}

	@GetMapping("/api/lives/{id}/comments")
	public Map<String, List<CommentResponse>> liveComments(@PathVariable String id) {
		return list(CommentTarget.LIVE, id);
	}

	@PostMapping(path = "/api/lives/{id}/comments", consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, CommentResponse> writeLiveComment(@AuthenticationPrincipal LoginUser me,
			@PathVariable String id, @RequestBody ContentRequest body) {
		return Map.of("comment", CommentResponse.from(service.create(me, CommentTarget.LIVE, id, body.content())));
	}

	@PatchMapping(path = "/api/comments/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, CommentResponse> edit(@AuthenticationPrincipal LoginUser me, @PathVariable Long id,
			@RequestBody ContentRequest body) {
		return Map.of("comment", CommentResponse.from(service.edit(me, id, body.content())));
	}

	@DeleteMapping("/api/comments/{id}")
	public Map<String, Boolean> delete(@AuthenticationPrincipal LoginUser me, @PathVariable Long id) {
		service.delete(me, id);
		return Map.of("ok", true);
	}

	private Map<String, List<CommentResponse>> list(CommentTarget type, String id) {
		return Map.of("comments", service.list(type, id).stream().map(CommentResponse::from).toList());
	}

}
