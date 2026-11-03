package dev.chzzk.vod.donation;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import dev.chzzk.vod.broadcast.Broadcast;
import dev.chzzk.vod.broadcast.BroadcastRepository;
import dev.chzzk.vod.common.ApiException;
import dev.chzzk.vod.live.LiveStream;
import dev.chzzk.vod.live.LiveStreamRepository;
import dev.chzzk.vod.payment.PaymentException;
import dev.chzzk.vod.payment.PaymentGateway;
import dev.chzzk.vod.payment.PaymentResult;
import dev.chzzk.vod.user.LoginUser;
import dev.chzzk.vod.user.User;
import dev.chzzk.vod.user.UserService;

/**
 * 도네이션(후원).
 *
 * <pre>
 * 1. request  : 로그인 사용자가 방송·금액 선택 → 서버가 받는 사람(방송 소유자)과 금액을 정해 PENDING 저장, 주문번호 발급
 * 2. 결제창    : 브라우저가 PG 결제창(토스페이먼츠 테스트 / 내장 테스트 PG)에서 결제 → successUrl 또는 failUrl 로 돌아옴
 * 3. confirm  : 서버가 금액·받는 사람을 다시 확인하고 PG 에 승인 요청 → PG 결과의 상태·주문번호·금액이 맞을 때만 PAID
 *    fail     : 결제 실패·취소를 기록 (PAID 는 되지 않음)
 * 4. webhook  : PG 의 승인 통지. 통지 내용은 믿지 않고 PG 에 결제를 조회해 확인한 뒤 같은 확정 절차를 탄다.
 * </pre>
 * 화면이 "성공"을 보여 주는 것과 상관없이 후원은 3·4 의 서버 확인을 거쳐야만 PAID 가 되고 합계에 들어간다.
 * PENDING → PAID 는 조건부 UPDATE 로 한 번만 일어나므로 승인 통지·승인 요청이 반복돼도 중복 적립되지 않는다.
 */
@Service
public class DonationService {

	private static final Logger log = LoggerFactory.getLogger(DonationService.class);
	private static final Set<String> CANCEL_CODES = Set.of("PAY_PROCESS_CANCELED", "USER_CANCEL");
	private static final Set<String> CLOSED_STATUSES = Set.of("CANCELED", "PARTIAL_CANCELED", "ABORTED", "EXPIRED");

	private final DonationRepository repository;
	private final BroadcastRepository broadcasts;
	private final LiveStreamRepository lives;
	private final UserService userService;
	private final PaymentGateway gateway;
	private final TransactionTemplate tx;
	private final long minAmount;
	private final long maxAmount;

	public DonationService(DonationRepository repository, BroadcastRepository broadcasts, LiveStreamRepository lives,
			UserService userService, PaymentGateway gateway, TransactionTemplate tx,
			@Value("${app.donation.min-amount}") long minAmount, @Value("${app.donation.max-amount}") long maxAmount) {
		this.repository = repository;
		this.broadcasts = broadcasts;
		this.lives = lives;
		this.userService = userService;
		this.gateway = gateway;
		this.tx = tx;
		this.minAmount = minAmount;
		this.maxAmount = maxAmount;
	}

	/** 결제창을 열 때 필요한 정보 */
	public record Checkout(String provider, String clientKey, String orderId, String orderName, long amount,
			String customerName) {
	}

	public record Requested(Donation donation, Checkout checkout) {
	}

	/** 방송별 확정된 후원 합계와 내역 */
	public record Summary(long total, int count, List<Donation> donations) {
	}

	// ---- 1. 후원 요청 ----

	@Transactional
	public Requested request(LoginUser me, DonationTarget type, String targetId, Long rawAmount, String rawMessage) {
		User donor = userService.require(me);
		if (type == null || targetId == null) {
			throw ApiException.badRequest("후원할 방송을 선택하세요.");
		}
		User recipient = ownerOf(type, targetId);
		if (recipient.getId().equals(donor.getId())) {
			throw ApiException.badRequest("자기 방송에는 후원할 수 없습니다.");
		}
		long amount = rawAmount == null ? 0 : rawAmount;
		if (amount < minAmount || amount > maxAmount) {
			throw ApiException.badRequest(String.format("후원 금액은 %,d원 ~ %,d원 사이로 선택하세요.", minAmount, maxAmount));
		}
		String message = rawMessage == null ? "" : rawMessage.trim();
		if (message.length() > Donation.MESSAGE_MAX) {
			throw ApiException.badRequest("후원 메시지는 " + Donation.MESSAGE_MAX + "자 이하로 입력하세요.");
		}
		String orderId = "don_" + UUID.randomUUID().toString().replace("-", "");
		Donation donation = repository.save(
				new Donation(orderId, donor, recipient, type, targetId, amount, message, gateway.provider()));
		Checkout checkout = new Checkout(gateway.provider(), gateway.clientKey(), orderId,
				recipient.getNickname() + "님 후원", amount, donor.getNickname());
		return new Requested(donation, checkout);
	}

	// ---- 3. 결제 승인 확인 ----

	/**
	 * 결제창 successUrl 로 돌아온 값(paymentKey, orderId, amount)으로 승인을 확인한다.
	 * successUrl 의 값은 사용자가 바꿀 수 있으므로 저장된 주문과 PG 응답으로만 판단한다.
	 */
	public Donation confirm(LoginUser me, String orderId, String paymentKey, Long amountParam) {
		Donation donation = requireDonor(me, orderId);
		if (paymentKey == null || paymentKey.isBlank()) {
			throw ApiException.badRequest("결제키가 없습니다.");
		}
		if (donation.getStatus() == DonationStatus.PAID) {
			if (paymentKey.equals(donation.getPaymentKey())) {
				return donation; // 새로고침 등으로 같은 승인 확인이 반복됨 → 그대로 돌려줌 (중복 적립 없음)
			}
			throw new ApiException(HttpStatus.CONFLICT, "이미 다른 결제로 확정된 후원입니다.");
		}
		if (!donation.isPending()) {
			throw new ApiException(HttpStatus.CONFLICT, "이미 실패·취소 처리된 후원입니다. 다시 후원해 주세요.");
		}
		// 금액 검증: 결제창에서 돌아온 금액이 서버가 정한 금액과 다르면 PG 승인 요청을 하지 않는다.
		if (amountParam == null || amountParam != donation.getAmount()) {
			markUnpaid(donation, DonationStatus.FAILED, "금액 불일치 (요청 " + donation.getAmount() + "원, 결제 " + amountParam + "원)");
			throw ApiException.badRequest("결제 금액이 후원 요청 금액과 달라 후원을 확정하지 않았습니다.");
		}
		// 수신 대상 검증: 받는 사람이 아직 이 방송의 소유자인지 (방송 삭제 등)
		requireSameRecipient(donation);

		PaymentResult result;
		try {
			result = gateway.confirm(paymentKey, orderId, donation.getAmount());
		}
		catch (PaymentException e) {
			if ("ALREADY_PROCESSED_PAYMENT".equals(e.getCode())) {
				// PG 에서는 이미 승인됨 (예: 승인 응답을 받기 전에 연결이 끊김) → 조회해서 같은 확인 절차를 탄다.
				result = gateway.find(paymentKey);
			}
			else {
				markUnpaid(donation, DonationStatus.FAILED, e.getCode() + ": " + e.getMessage());
				throw ApiException.badRequest("결제 승인에 실패했습니다: " + e.getMessage());
			}
		}
		return applyApproved(donation, result, paymentKey);
	}

	/** 결제창 failUrl: 실패·취소 기록. PAID 인 후원은 바뀌지 않는다. */
	public Donation fail(LoginUser me, String orderId, String code, String message) {
		Donation donation = requireDonor(me, orderId);
		DonationStatus status = code != null && CANCEL_CODES.contains(code) ? DonationStatus.CANCELED
				: DonationStatus.FAILED;
		String reason = (code == null ? "UNKNOWN" : code) + (message == null ? "" : ": " + message);
		markUnpaid(donation, status, reason.length() > 200 ? reason.substring(0, 200) : reason);
		return reload(donation);
	}

	// ---- 4. 승인 통지 (웹훅) ----

	/**
	 * PG 승인 통지 처리. 통지 본문은 위조될 수 있으므로 결제키로 PG 에 직접 조회한 결과로만 판단한다.
	 * @return 처리 결과 (confirmed | already-processed | canceled | ignored)
	 */
	public String handleNotification(String paymentKey) {
		if (paymentKey == null || paymentKey.isBlank()) {
			return "ignored";
		}
		PaymentResult result;
		try {
			result = gateway.find(paymentKey);
		}
		catch (PaymentException e) {
			log.warn("승인 통지 확인 실패 paymentKey={} : {}", paymentKey, e.getMessage());
			return "ignored";
		}
		Donation donation = repository.findByOrderId(result.orderId()).orElse(null);
		if (donation == null) {
			return "ignored";
		}
		if (CLOSED_STATUSES.contains(result.status())) {
			markUnpaid(donation, DonationStatus.CANCELED, "PG 통지: " + result.status());
			return "canceled";
		}
		if (!result.isDone()) {
			return "ignored";
		}
		if (donation.getStatus() == DonationStatus.PAID) {
			return "already-processed";
		}
		try {
			applyApproved(donation, result, paymentKey);
			return "confirmed";
		}
		catch (ApiException e) {
			log.warn("승인 통지 검증 실패 orderId={} : {}", donation.getOrderId(), e.getMessage());
			return "ignored";
		}
	}

	// ---- 5. 조회 ----

	@Transactional(readOnly = true)
	public Summary summary(DonationTarget type, String targetId) {
		ownerOrNull(type, targetId, true);
		List<Donation> paid = repository.findByTargetTypeAndTargetIdAndStatusOrderByPaidAtDesc(type, targetId,
				DonationStatus.PAID);
		return new Summary(repository.sumPaid(type, targetId), paid.size(), paid);
	}

	@Transactional(readOnly = true)
	public Donation get(LoginUser me, String orderId) {
		return requireDonor(me, orderId);
	}

	// ---- 내부 ----

	/** PG 승인 결과를 검증하고 확정한다. 상태 DONE, 주문번호·금액 일치, 받는 사람 일치를 모두 만족해야 한다. */
	private Donation applyApproved(Donation donation, PaymentResult result, String paymentKey) {
		if (!result.isDone()) {
			markUnpaid(donation, DonationStatus.FAILED, "PG 승인 상태가 완료가 아님: " + result.status());
			throw ApiException.badRequest("결제가 승인되지 않았습니다. (" + result.status() + ")");
		}
		if (!donation.getOrderId().equals(result.orderId()) || result.totalAmount() != donation.getAmount()
				|| !paymentKey.equals(result.paymentKey())) {
			markUnpaid(donation, DonationStatus.FAILED, "PG 승인 내용 불일치");
			throw ApiException.badRequest("결제 승인 내용(주문번호·금액)이 후원 요청과 달라 확정하지 않았습니다.");
		}
		try {
			Integer changed = tx.execute(status -> repository.markPaid(donation.getId(), paymentKey, result.method(),
					Instant.now()));
			if (changed == null || changed == 0) {
				log.info("이미 처리된 후원 orderId={} — 중복 승인은 적립하지 않음", donation.getOrderId());
			}
		}
		catch (DataIntegrityViolationException e) {
			throw new ApiException(HttpStatus.CONFLICT, "이미 다른 후원에 사용된 결제입니다.");
		}
		Donation saved = reload(donation);
		if (saved.getStatus() != DonationStatus.PAID || !paymentKey.equals(saved.getPaymentKey())) {
			throw new ApiException(HttpStatus.CONFLICT, "후원 상태가 바뀌어 확정하지 못했습니다.");
		}
		return saved;
	}

	private void requireSameRecipient(Donation donation) {
		User owner = ownerOrNull(donation.getTargetType(), donation.getTargetId(), false);
		if (owner == null || !owner.getId().equals(donation.getRecipient().getId())) {
			markUnpaid(donation, DonationStatus.FAILED, "받는 방송자가 바뀌었거나 방송이 삭제됨");
			throw new ApiException(HttpStatus.CONFLICT, "후원받을 방송자가 바뀌었거나 방송이 삭제되어 후원을 확정하지 않았습니다.");
		}
	}

	private Donation requireDonor(LoginUser me, String orderId) {
		if (me == null) {
			throw ApiException.unauthorized();
		}
		Donation donation = repository.findByOrderId(orderId == null ? "" : orderId)
			.orElseThrow(() -> ApiException.notFound("후원 주문을 찾을 수 없습니다."));
		if (!donation.getDonor().getId().equals(me.getId())) {
			throw ApiException.forbidden("본인의 후원만 확인할 수 있습니다.");
		}
		return donation;
	}

	private void markUnpaid(Donation donation, DonationStatus status, String reason) {
		tx.executeWithoutResult(s -> repository.markUnpaid(donation.getId(), status, reason));
	}

	private Donation reload(Donation donation) {
		return tx.execute(s -> repository.findById(donation.getId()).orElseThrow());
	}

	private User ownerOf(DonationTarget type, String targetId) {
		User owner = ownerOrNull(type, targetId, true);
		if (owner == null) {
			throw ApiException.badRequest("방송자(소유자)가 없는 이전 단계 방송에는 후원할 수 없습니다.");
		}
		return owner;
	}

	/** 방송의 소유자. 방송이 없으면 requireExists 에 따라 404 또는 null */
	private User ownerOrNull(DonationTarget type, String targetId, boolean requireExists) {
		if (type == DonationTarget.BROADCAST) {
			Broadcast b = broadcasts.findById(targetId).orElse(null);
			if (b == null && requireExists) {
				throw ApiException.notFound("방송을 찾을 수 없습니다.");
			}
			return b == null ? null : b.getOwner();
		}
		LiveStream l = lives.findById(targetId).orElse(null);
		if (l == null && requireExists) {
			throw ApiException.notFound("라이브 방송을 찾을 수 없습니다.");
		}
		return l == null ? null : l.getOwner();
	}

}
