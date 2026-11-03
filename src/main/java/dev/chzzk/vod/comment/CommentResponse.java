package dev.chzzk.vod.comment;

import java.time.Instant;

import dev.chzzk.vod.user.UserSummary;

public record CommentResponse(Long id, String content, UserSummary author, Instant createdAt, Instant updatedAt) {

	public static CommentResponse from(Comment c) {
		return new CommentResponse(c.getId(), c.getContent(), UserSummary.of(c.getAuthor()), c.getCreatedAt(),
				c.getUpdatedAt());
	}

}
