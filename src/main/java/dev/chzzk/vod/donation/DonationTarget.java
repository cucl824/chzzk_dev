package dev.chzzk.vod.donation;

import com.fasterxml.jackson.annotation.JsonValue;

/** 후원이 이뤄진 방송. 후원금은 이 방송의 소유자(방송자)에게 간다. */
public enum DonationTarget {
	/** 녹화 방송(다시보기) */
	BROADCAST,
	/** 라이브 방송 */
	LIVE;

	@JsonValue
	public String json() {
		return name().toLowerCase();
	}
}
