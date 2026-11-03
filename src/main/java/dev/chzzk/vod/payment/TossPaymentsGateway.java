package dev.chzzk.vod.payment;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 토스페이먼츠 결제 승인 API 연동 (테스트 키를 쓰면 실제 돈이 나가지 않는 테스트 모드).
 * <ul>
 * <li>승인: POST /v1/payments/confirm {paymentKey, orderId, amount}</li>
 * <li>조회: GET /v1/payments/{paymentKey}</li>
 * <li>인증: Basic base64("시크릿키:")</li>
 * </ul>
 * https://docs.tosspayments.com/reference#결제-승인
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "toss")
public class TossPaymentsGateway implements PaymentGateway {

	private final RestClient client;
	private final String clientKey;
	private final ObjectMapper mapper;

	@Autowired
	public TossPaymentsGateway(ObjectMapper mapper, @Value("${app.payment.toss.api-url}") String apiUrl,
			@Value("${app.payment.toss.client-key}") String clientKey,
			@Value("${app.payment.toss.secret-key}") String secretKey) {
		this(RestClient.builder(), mapper, apiUrl, clientKey, secretKey);
	}

	/** 테스트에서 가짜 응답 서버(MockRestServiceServer)를 붙일 수 있도록 builder 를 받는다. */
	TossPaymentsGateway(RestClient.Builder builder, ObjectMapper mapper, String apiUrl, String clientKey,
			String secretKey) {
		if (clientKey.isBlank() || secretKey.isBlank()) {
			throw new IllegalStateException(
					"PAYMENT_PROVIDER=toss 이면 TOSS_CLIENT_KEY, TOSS_SECRET_KEY(테스트 키)를 설정해야 합니다. README 참고");
		}
		if (!clientKey.startsWith("test_") || !secretKey.startsWith("test_")) {
			throw new IllegalStateException("실제 결제를 막기 위해 토스페이먼츠 테스트 키(test_로 시작)만 사용할 수 있습니다.");
		}
		String basic = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
		this.client = builder.baseUrl(apiUrl).defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic).build();
		this.clientKey = clientKey;
		this.mapper = mapper;
	}

	@Override
	public String provider() {
		return "toss";
	}

	@Override
	public String clientKey() {
		return clientKey;
	}

	@Override
	public PaymentResult confirm(String paymentKey, String orderId, long amount) {
		try {
			JsonNode body = client.post()
				.uri("/v1/payments/confirm")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
				.retrieve()
				.body(JsonNode.class);
			return toResult(body);
		}
		catch (RestClientResponseException e) {
			throw toException(e);
		}
	}

	@Override
	public PaymentResult find(String paymentKey) {
		try {
			return toResult(client.get().uri("/v1/payments/{paymentKey}", paymentKey).retrieve().body(JsonNode.class));
		}
		catch (RestClientResponseException e) {
			throw toException(e);
		}
	}

	private static PaymentResult toResult(JsonNode body) {
		if (body == null) {
			throw new PaymentException("EMPTY_RESPONSE", "결제 서비스 응답이 비어 있습니다.");
		}
		return new PaymentResult(body.path("paymentKey").asString(""), body.path("orderId").asString(""),
				body.path("status").asString(""), body.path("totalAmount").asLong(-1), body.path("method").asString(""));
	}

	private PaymentException toException(RestClientResponseException e) {
		try {
			JsonNode error = mapper.readTree(e.getResponseBodyAsString());
			return new PaymentException(error.path("code").asString("PAYMENT_ERROR"),
					error.path("message").asString("결제 서비스 오류"));
		}
		catch (RuntimeException parseError) {
			return new PaymentException("PAYMENT_ERROR", "결제 서비스 오류 (HTTP " + e.getStatusCode().value() + ")");
		}
	}

}
