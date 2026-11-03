package dev.chzzk.vod.live;

import java.time.Instant;

import dev.chzzk.vod.user.UserSummary;

/** 라이브 방송 응답. 송출 토큰은 포함하지 않는다. */
public record LiveResponse(String id, String title, LiveStatus status, int viewerCount, Instant startedAt,
		Instant endedAt, String vodBroadcastId, UserSummary owner) {

	public static LiveResponse from(LiveStream live, int viewerCount) {
		return new LiveResponse(live.getId(), live.getTitle(), live.getStatus(), live.isLive() ? viewerCount : 0,
				live.getStartedAt(), live.getEndedAt(), live.getVodBroadcastId(), UserSummary.of(live.getOwner()));
	}

}
