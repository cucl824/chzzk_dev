package dev.chzzk.vod.payment;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내장 테스트 PG 의 결제창이 부르는 API. 실제 PG 의 결제창 서버에 해당하므로 우리 서비스의 로그인과는 무관하다.
 * (PAYMENT_PROVIDER=toss 이면 등록되지 않는다)
 */
@RestController
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "sandbox", matchIfMissing = true)
public class SandboxPgController {

	private final SandboxPaymentGateway gateway;

	public SandboxPgController(SandboxPaymentGateway gateway) {
		this.gateway = gateway;
	}

	public record AuthorizeRequest(String orderId, Long amount) {
	}

	/** 결제창 "결제 성공": 결제키를 발급한다. 결제창은 이 값을 successUrl 에 붙여 돌려보낸다. */
	@PostMapping(path = "/api/sandbox-pg/authorize", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, String> authorize(@RequestBody AuthorizeRequest body) {
		SandboxPayment payment = gateway.authorize(body.orderId(), body.amount() == null ? 0 : body.amount());
		return Map.of("paymentKey", payment.getPaymentKey(), "orderId", payment.getOrderId());
	}

}
