package dev.chzzk.vod.donation;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 후원 상태. PENDING 에서만 다른 상태로 바뀌며, PAID 는 서버가 PG 승인을 확인했을 때만 된다.
 *
 * <pre>
 * PENDING ─(서버 승인 확인: 금액·주문·상태 DONE)→ PAID
 *         ─(결제 실패 / PG 승인 거절)→ FAILED
 *         ─(사용자 결제 취소)→ CANCELED
 * </pre>
 */
public enum DonationStatus {
	PENDING, PAID, FAILED, CANCELED;

	@JsonValue
	public String json() {
		return name().toLowerCase();
	}
}
