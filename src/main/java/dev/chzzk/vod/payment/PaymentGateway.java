package dev.chzzk.vod.payment;

/**
 * 결제 서비스(PG) 연동. 결제창에서 사용자가 결제를 마치면 결제키(paymentKey)가 돌아오고,
 * 서버가 그 결제키로 PG 에 승인을 요청해야 실제로 결제가 완료된다.
 *
 * <ul>
 * <li>{@code toss} — 토스페이먼츠 테스트 모드 ({@link TossPaymentsGateway})</li>
 * <li>{@code sandbox} — 키 없이 쓰는 내장 테스트 PG ({@link SandboxPaymentGateway})</li>
 * </ul>
 */
public interface PaymentGateway {

	/** 화면에 알려 줄 PG 이름 (toss | sandbox) */
	String provider();

	/** 결제창에 넘길 공개 키. 내장 테스트 PG 는 null */
	String clientKey();

	/** 결제 승인 요청. 금액·주문번호가 결제창에서 결제한 내용과 다르면 PG 가 거절한다. */
	PaymentResult confirm(String paymentKey, String orderId, long amount);

	/** 결제 조회. 승인 통지(웹훅)를 받으면 통지 내용을 믿지 않고 이것으로 PG 에 직접 확인한다. */
	PaymentResult find(String paymentKey);

}
