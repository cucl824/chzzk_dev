package dev.chzzk.vod.donation;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import dev.chzzk.vod.user.User;

/**
 * 후원 한 건 = PG 결제 한 건.
 *
 * <p>중복 적립 방지 저장 구조:
 * <ul>
 * <li>{@code orderId} UNIQUE — 결제창에 넘기는 주문번호. 후원 1건에 하나.</li>
 * <li>{@code paymentKey} UNIQUE — PG 결제키. 같은 결제가 두 후원에 붙을 수 없다.</li>
 * <li>{@code status} — PENDING → PAID 전환은 "status = PENDING 인 경우에만" 조건부 UPDATE 로 한 번만 일어난다.
 * 같은 승인 통지가 여러 번 와도 두 번째부터는 바뀌는 행이 없다.</li>
 * </ul>
 * 금액과 받는 사람(recipient)은 후원 요청 때 서버가 정하고, 승인 확인 때 PG 결과와 다시 대조한다.
 */
@Entity
@Table(name = "donations", indexes = @Index(name = "idx_donations_target", columnList = "targetType, targetId, status"))
public class Donation {

	public static final int MESSAGE_MAX = 100;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 64)
	private String orderId;

	@Column(unique = true, length = 200)
	private String paymentKey;

	@ManyToOne(optional = false)
	@JoinColumn(name = "donor_id", nullable = false)
	private User donor;

	/** 후원받는 방송자 = 요청 시점의 방송 소유자 */
	@ManyToOne(optional = false)
	@JoinColumn(name = "recipient_id", nullable = false)
	private User recipient;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private DonationTarget targetType;

	@Column(nullable = false, length = 36)
	private String targetId;

	@Column(nullable = false)
	private long amount;

	@Column(nullable = false, length = MESSAGE_MAX)
	private String message;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private DonationStatus status;

	@Column(nullable = false, length = 16)
	private String provider;

	@Column(length = 40)
	private String method;

	@Column(length = 200)
	private String failReason;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant paidAt;

	protected Donation() {
	}

	public Donation(String orderId, User donor, User recipient, DonationTarget targetType, String targetId, long amount,
			String message, String provider) {
		this.orderId = orderId;
		this.donor = donor;
		this.recipient = recipient;
		this.targetType = targetType;
		this.targetId = targetId;
		this.amount = amount;
		this.message = message;
		this.provider = provider;
		this.status = DonationStatus.PENDING;
		this.createdAt = Instant.now();
	}

	public boolean isPending() {
		return status == DonationStatus.PENDING;
	}

	public Long getId() {
		return id;
	}

	public String getOrderId() {
		return orderId;
	}

	public String getPaymentKey() {
		return paymentKey;
	}

	public User getDonor() {
		return donor;
	}

	public User getRecipient() {
		return recipient;
	}

	public DonationTarget getTargetType() {
		return targetType;
	}

	public String getTargetId() {
		return targetId;
	}

	public long getAmount() {
		return amount;
	}

	public String getMessage() {
		return message;
	}

	public DonationStatus getStatus() {
		return status;
	}

	public String getProvider() {
		return provider;
	}

	public String getMethod() {
		return method;
	}

	public String getFailReason() {
		return failReason;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getPaidAt() {
		return paidAt;
	}

}
