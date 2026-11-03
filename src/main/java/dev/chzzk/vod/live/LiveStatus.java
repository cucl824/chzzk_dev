package dev.chzzk.vod.live;

import com.fasterxml.jackson.annotation.JsonValue;

/** 라이브 방송 상태. */
public enum LiveStatus {
	/** 방송 중 */
	LIVE,
	/** 방송 종료 */
	ENDED;

	@JsonValue
	public String json() {
		return name().toLowerCase();
	}
}
