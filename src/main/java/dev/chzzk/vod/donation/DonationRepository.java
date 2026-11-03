package dev.chzzk.vod.donation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DonationRepository extends JpaRepository<Donation, Long> {

	Optional<Donation> findByOrderId(String orderId);

	Optional<Donation> findByPaymentKey(String paymentKey);

	List<Donation> findByTargetTypeAndTargetIdAndStatusOrderByPaidAtDesc(DonationTarget targetType, String targetId,
			DonationStatus status);

	@Query("select coalesce(sum(d.amount), 0) from Donation d "
			+ "where d.targetType = :type and d.targetId = :id and d.status = dev.chzzk.vod.donation.DonationStatus.PAID")
	long sumPaid(@Param("type") DonationTarget type, @Param("id") String id);

	/**
	 * 후원 확정. PENDING 인 경우에만 PAID 로 바꾸므로 같은 승인이 여러 번 처리돼도 한 번만 적용된다.
	 * @return 바뀐 행 수 (1 = 이번에 확정, 0 = 이미 처리됨)
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Donation d set d.status = dev.chzzk.vod.donation.DonationStatus.PAID, d.paymentKey = :paymentKey, "
			+ "d.method = :method, d.paidAt = :paidAt "
			+ "where d.id = :id and d.status = dev.chzzk.vod.donation.DonationStatus.PENDING")
	int markPaid(@Param("id") Long id, @Param("paymentKey") String paymentKey, @Param("method") String method,
			@Param("paidAt") Instant paidAt);

	/** 실패·취소 처리. 역시 PENDING 인 경우에만 바뀐다. (이미 PAID 인 후원을 실패로 되돌리지 않음) */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Donation d set d.status = :status, d.failReason = :reason "
			+ "where d.id = :id and d.status = dev.chzzk.vod.donation.DonationStatus.PENDING")
	int markUnpaid(@Param("id") Long id, @Param("status") DonationStatus status, @Param("reason") String reason);

}
