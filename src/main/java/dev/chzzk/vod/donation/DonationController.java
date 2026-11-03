package dev.chzzk.vod.donation;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.chzzk.vod.common.ApiException;
import dev.chzzk.vod.user.LoginUser;

/**
 * 도네이션 API. 후원 요청·승인 확인·실패 기록은 로그인한 후원자만, 방송별 후원 내역·합계는 누구나 조회한다.
 * PG 승인 통지(웹훅)는 PG 서버가 부르므로 로그인 없이 받고, 내용은 PG 조회로 확인한다.
 */
@RestController
public class DonationController {

	private final DonationService service;

	public DonationController(DonationService service) {
		this.service = service;
	}

	public record DonateRequest(String targetType, String targetId, Long amount, String message) {
	}

	public record ConfirmRequest(String orderId, String paymentKey, Long amount) {
	}

	public record FailRequest(String code, String message) {
	}

	/** 토스페이먼츠 웹훅 형식: {"eventType":"PAYMENT_STATUS_CHANGED","data":{"paymentKey":"...","orderId":"...","status":"DONE"}} */
	public record WebhookRequest(String eventType, WebhookData data) {
	}

	public record WebhookData(String paymentKey, String orderId, String status) {
	}

	public record DonationsView(long total, int count, List<DonationResponse> donations) {
	}

	@PostMapping(path = "/api/donations", consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> request(@AuthenticationPrincipal LoginUser me, @RequestBody DonateRequest body) {
		DonationService.Requested requested = service.request(me, target(body.targetType()), body.targetId(),
				body.amount(), body.message());
		return Map.of("donation", DonationResponse.from(requested.donation()), "checkout", requested.checkout());
	}

	@PostMapping(path = "/api/donations/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, DonationResponse> confirm(@AuthenticationPrincipal LoginUser me,
			@RequestBody ConfirmRequest body) {
		return Map.of("donation",
				DonationResponse.from(service.confirm(me, body.orderId(), body.paymentKey(), body.amount())));
	}

	@PostMapping(path = "/api/donations/{orderId}/fail", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, DonationResponse> fail(@AuthenticationPrincipal LoginUser me, @PathVariable String orderId,
			@RequestBody FailRequest body) {
		return Map.of("donation", DonationResponse.from(service.fail(me, orderId, body.code(), body.message())));
	}

	@GetMapping("/api/donations/{orderId}")
	public Map<String, DonationResponse> get(@AuthenticationPrincipal LoginUser me, @PathVariable String orderId) {
		return Map.of("donation", DonationResponse.from(service.get(me, orderId)));
	}

	@GetMapping("/api/broadcasts/{id}/donations")
	public DonationsView broadcastDonations(@PathVariable String id) {
		return view(service.summary(DonationTarget.BROADCAST, id));
	}

	@GetMapping("/api/lives/{id}/donations")
	public DonationsView liveDonations(@PathVariable String id) {
		return view(service.summary(DonationTarget.LIVE, id));
	}

	/** PG 승인 통지. 같은 통지가 여러 번 와도 항상 200 으로 받고, 후원은 한 번만 확정된다. */
	@PostMapping(path = "/api/payments/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, String> webhook(@RequestBody WebhookRequest body) {
		String paymentKey = body.data() == null ? null : body.data().paymentKey();
		return Map.of("result", service.handleNotification(paymentKey));
	}

	private static DonationsView view(DonationService.Summary summary) {
		return new DonationsView(summary.total(), summary.count(),
				summary.donations().stream().map(DonationResponse::from).toList());
	}

	private static DonationTarget target(String raw) {
		if ("broadcast".equalsIgnoreCase(raw)) {
			return DonationTarget.BROADCAST;
		}
		if ("live".equalsIgnoreCase(raw)) {
			return DonationTarget.LIVE;
		}
		throw ApiException.badRequest("후원 대상은 broadcast 또는 live 입니다.");
	}

}
