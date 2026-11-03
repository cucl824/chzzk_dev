package dev.chzzk.vod.payment;

/**
 * PG 가 알려 준 결제 상태.
 *
 * @param status 토스페이먼츠 결제 상태와 같은 값: DONE(승인 완료), IN_PROGRESS(인증만 됨, 승인 전), CANCELED, ABORTED, EXPIRED 등
 */
public record PaymentResult(String paymentKey, String orderId, String status, long totalAmount, String method) {

	public boolean isDone() {
		return "DONE".equals(status);
	}

}
