package dev.chzzk.vod.broadcast;

import java.time.Instant;

/** API 응답 형식. 화면(static/*.html)이 사용하는 필드 이름과 맞춘다. */
public record BroadcastResponse(String id, String title, String description, SourceType sourceType,
		String videoUrl, String fileName, String mimeType, Long size, long viewCount, Instant createdAt) {

	public static BroadcastResponse from(Broadcast b) {
		return new BroadcastResponse(b.getId(), b.getTitle(), b.getDescription(), b.getSourceType(),
				b.getVideoUrl(), b.getFileName(), b.getMimeType(), b.getSize(), b.getViewCount(), b.getCreatedAt());
	}

}
