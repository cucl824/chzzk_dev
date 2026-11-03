package dev.chzzk.vod.payment;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 내장 테스트 PG 가 보관하는 결제 건. 실제 PG 의 결제 기록 역할이며 우리 서비스의 후원(Donation)과는 별개다.
 * 결제창에서 "결제 성공"을 누르면 IN_PROGRESS(인증됨) 로 생기고, 서버의 승인 요청을 받아야 DONE 이 된다.
 */
@Entity
@Table(name = "sandbox_payments")
public class SandboxPayment {

	@Id
	@Column(length = 64)
	private String paymentKey;

	@Column(nullable = false, unique = true, length = 64)
	private String orderId;

	@Column(nullable = false)
	private long amount;

	@Column(nullable = false, length = 16)
	private String status;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant approvedAt;

	protected SandboxPayment() {
	}

	SandboxPayment(String paymentKey, String orderId, long amount) {
		this.paymentKey = paymentKey;
		this.orderId = orderId;
		this.amount = amount;
		this.status = "IN_PROGRESS";
		this.createdAt = Instant.now();
	}

	void approve() {
		status = "DONE";
		approvedAt = Instant.now();
	}

	PaymentResult toResult() {
		return new PaymentResult(paymentKey, orderId, status, amount, "테스트 카드");
	}

	public String getPaymentKey() {
		return paymentKey;
	}

	public String getOrderId() {
		return orderId;
	}

	public long getAmount() {
		return amount;
	}

	public String getStatus() {
		return status;
	}

}
