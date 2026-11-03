package dev.chzzk.vod.payment;

/** PG 가 승인·조회를 거절했을 때. code 는 PG 의 오류 코드 (예: REJECT_CARD_PAYMENT, ALREADY_PROCESSED_PAYMENT) */
public class PaymentException extends RuntimeException {

	private final String code;

	public PaymentException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String getCode() {
		return code;
	}

}
