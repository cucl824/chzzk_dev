package dev.chzzk.vod.payment;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 키 없이 쓰는 내장 테스트 PG (기본값). 토스페이먼츠 테스트 모드와 같은 흐름을 흉내 낸다.
 *
 * <pre>
 * 결제창(/sandbox-pg.html) "결제 성공" → authorize: 결제키 발급(IN_PROGRESS) → successUrl 로 이동
 * 서버 → confirm: 주문번호·금액이 결제창에서 결제한 것과 같아야 DONE (다르면 거절, 이미 승인됐으면 ALREADY_PROCESSED_PAYMENT)
 * 승인 통지(웹훅) 확인 → find: 결제 상태 조회
 * </pre>
 *
 * 실제 돈은 오가지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "sandbox", matchIfMissing = true)
public class SandboxPaymentGateway implements PaymentGateway {

	private final SandboxPaymentRepository repository;

	public SandboxPaymentGateway(SandboxPaymentRepository repository) {
		this.repository = repository;
	}

	@Override
	public String provider() {
		return "sandbox";
	}

	@Override
	public String clientKey() {
		return null;
	}

	/** 결제창에서 사용자가 결제(카드 인증)를 마친 것에 해당한다. 아직 승인 전이라 돈이 확정되지 않는다. */
	@Transactional
	public SandboxPayment authorize(String orderId, long amount) {
		if (orderId == null || orderId.isBlank() || amount <= 0) {
			throw new PaymentException("INVALID_REQUEST", "주문번호와 금액이 필요합니다.");
		}
		return repository.findByOrderId(orderId)
			.orElseGet(() -> repository.save(new SandboxPayment("sbx_" + UUID.randomUUID().toString().replace("-", ""),
					orderId, amount)));
	}

	@Override
	@Transactional
	public PaymentResult confirm(String paymentKey, String orderId, long amount) {
		SandboxPayment payment = repository.findById(paymentKey)
			.orElseThrow(() -> new PaymentException("NOT_FOUND_PAYMENT", "존재하지 않는 결제입니다."));
		if (!payment.getOrderId().equals(orderId) || payment.getAmount() != amount) {
			throw new PaymentException("INVALID_REQUEST", "결제 정보(주문번호·금액)가 결제창에서 결제한 내용과 다릅니다.");
		}
		if ("DONE".equals(payment.getStatus())) {
			throw new PaymentException("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제입니다.");
		}
		payment.approve();
		return payment.toResult();
	}

	@Override
	@Transactional(readOnly = true)
	public PaymentResult find(String paymentKey) {
		return repository.findById(paymentKey)
			.map(SandboxPayment::toResult)
			.orElseThrow(() -> new PaymentException("NOT_FOUND_PAYMENT", "존재하지 않는 결제입니다."));
	}

}
