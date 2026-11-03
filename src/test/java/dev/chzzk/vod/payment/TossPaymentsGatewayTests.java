package dev.chzzk.vod.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

/** 토스페이먼츠 승인·조회 API 요청 형식과 응답·오류 해석 (실제 토스 서버 대신 가짜 응답 서버 사용) */
class TossPaymentsGatewayTests {

	private static final String SECRET = "test_sk_example";

	MockRestServiceServer server;
	TossPaymentsGateway gateway;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		gateway = new TossPaymentsGateway(builder, JsonMapper.builder().build(), "https://api.tosspayments.com",
				"test_ck_example", SECRET);
	}

	@Test
	void confirmSendsBasicAuthAndParsesResult() {
		String basic = "Basic " + Base64.getEncoder().encodeToString((SECRET + ":").getBytes(StandardCharsets.UTF_8));
		server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", basic))
			.andExpect(content().json("{\"paymentKey\":\"pk_1\",\"orderId\":\"don_1\",\"amount\":5000}"))
			.andRespond(withSuccess("{\"paymentKey\":\"pk_1\",\"orderId\":\"don_1\",\"status\":\"DONE\","
					+ "\"totalAmount\":5000,\"method\":\"카드\"}", MediaType.APPLICATION_JSON));

		PaymentResult result = gateway.confirm("pk_1", "don_1", 5000);
		assertTrue(result.isDone());
		assertEquals(5000, result.totalAmount());
		assertEquals("don_1", result.orderId());
		server.verify();
	}

	@Test
	void errorResponseBecomesPaymentException() {
		server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
			.andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"ALREADY_PROCESSED_PAYMENT\",\"message\":\"이미 처리된 결제 입니다.\"}"));

		PaymentException e = assertThrows(PaymentException.class, () -> gateway.confirm("pk_1", "don_1", 5000));
		assertEquals("ALREADY_PROCESSED_PAYMENT", e.getCode());
	}

	@Test
	void findQueriesPaymentByKey() {
		server.expect(requestTo("https://api.tosspayments.com/v1/payments/pk_1"))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("{\"paymentKey\":\"pk_1\",\"orderId\":\"don_1\",\"status\":\"CANCELED\","
					+ "\"totalAmount\":5000}", MediaType.APPLICATION_JSON));
		assertEquals("CANCELED", gateway.find("pk_1").status());
	}

	@Test
	void refusesLiveKeys() {
		assertThrows(IllegalStateException.class, () -> new TossPaymentsGateway(RestClient.builder(),
				JsonMapper.builder().build(), "https://api.tosspayments.com", "live_ck_x", "live_sk_x"));
		assertThrows(IllegalStateException.class, () -> new TossPaymentsGateway(RestClient.builder(),
				JsonMapper.builder().build(), "https://api.tosspayments.com", "", ""));
	}

}
