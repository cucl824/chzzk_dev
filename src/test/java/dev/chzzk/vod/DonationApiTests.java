package dev.chzzk.vod;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import dev.chzzk.vod.payment.SandboxPaymentGateway;

/**
 * 도네이션: 내장 테스트 PG 로 결제 요청 → 결제창 결과 → 서버 승인 확인 → 확정.
 * 성공·실패·취소, 승인 확인·승인 통지 중복, 금액 조작, 다른 사용자 확인을 검사한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DonationApiTests {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("app.upload-dir", () -> tempDir.resolve("uploads").toString());
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:donation;DB_CLOSE_DELAY=-1");
		registry.add("app.payment.provider", () -> "sandbox");
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	SandboxPaymentGateway sandbox;

	TestUsers.Login owner;
	TestUsers.Login donor;
	String broadcastId;

	@BeforeEach
	void setUp() throws Exception {
		owner = TestUsers.signup(mvc, "streamer");
		donor = TestUsers.signup(mvc, "fan");
		String body = mvc.perform(multipart("/api/broadcasts").with(csrf()).session(owner.session())
			.param("title", "후원 받을 방송").param("videoUrl", "https://example.com/v.mp4"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		broadcastId = JsonPath.read(body, "$.broadcast.id");
	}

	private ResultActions requestDonation(TestUsers.Login as, long amount) throws Exception {
		var builder = post("/api/donations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetType\":\"broadcast\",\"targetId\":\"" + broadcastId + "\",\"amount\":" + amount
					+ ",\"message\":\"응원해요\"}");
		return mvc.perform(as == null ? builder : builder.session(as.session()));
	}

	private String newOrder(long amount) throws Exception {
		String body = requestDonation(donor, amount)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.donation.status").value("pending"))
			.andExpect(jsonPath("$.donation.recipient.id").value(owner.id()))
			.andExpect(jsonPath("$.checkout.provider").value("sandbox"))
			.andExpect(jsonPath("$.checkout.amount").value(amount))
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.checkout.orderId");
	}

	/** 테스트 PG 결제창에서 "결제 성공"을 누른 것 (결제키 발급, 아직 승인 전) */
	private String payInCheckout(String orderId, long amount) throws Exception {
		String body = mvc.perform(post("/api/sandbox-pg/authorize").contentType(MediaType.APPLICATION_JSON)
			.content("{\"orderId\":\"" + orderId + "\",\"amount\":" + amount + "}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.paymentKey");
	}

	private ResultActions confirm(TestUsers.Login as, String orderId, String paymentKey, long amount) throws Exception {
		return mvc.perform(post("/api/donations/confirm").with(csrf()).session(as.session())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"orderId\":\"" + orderId + "\",\"paymentKey\":\"" + paymentKey + "\",\"amount\":" + amount + "}"));
	}

	/** PG 승인 통지 (CSRF·로그인 없음, PG 서버가 보내는 요청) */
	private ResultActions notify(String paymentKey, String orderId) throws Exception {
		return mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON)
			.content("{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{\"paymentKey\":\"" + paymentKey
					+ "\",\"orderId\":\"" + orderId + "\",\"status\":\"DONE\"}}"));
	}

	private void expectTotal(long total, int count) throws Exception {
		mvc.perform(get("/api/broadcasts/" + broadcastId + "/donations"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(total))
			.andExpect(jsonPath("$.count").value(count))
			.andExpect(jsonPath("$.donations", hasSize(count)));
	}

	@Test
	void successIsConfirmedOnceEvenIfConfirmAndNotificationRepeat() throws Exception {
		String orderId = newOrder(5000);
		String paymentKey = payInCheckout(orderId, 5000);
		expectTotal(0, 0); // 결제창 성공만으로는 확정되지 않음

		confirm(donor, orderId, paymentKey, 5000)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.donation.status").value("paid"))
			.andExpect(jsonPath("$.donation.amount").value(5000));
		expectTotal(5000, 1);

		// 결과 화면 새로고침 → 같은 승인 확인 반복: 그대로 성공 응답, 적립은 1번
		confirm(donor, orderId, paymentKey, 5000).andExpect(status().isOk())
			.andExpect(jsonPath("$.donation.status").value("paid"));
		// 같은 승인 통지가 여러 번 와도 적립은 1번
		notify(paymentKey, orderId).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("already-processed"));
		notify(paymentKey, orderId).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("already-processed"));
		expectTotal(5000, 1);

		mvc.perform(get("/api/broadcasts/" + broadcastId + "/donations"))
			.andExpect(jsonPath("$.donations[0].donor.id").value(donor.id()))
			.andExpect(jsonPath("$.donations[0].message").value("응원해요"));
	}

	@Test
	void notificationAloneConfirmsOnceAfterServerVerifiesWithPg() throws Exception {
		String orderId = newOrder(3000);
		String paymentKey = payInCheckout(orderId, 3000);

		// 아직 PG 승인 전(IN_PROGRESS)에 온 통지는 무시
		notify(paymentKey, orderId).andExpect(jsonPath("$.result").value("ignored"));
		expectTotal(0, 0);

		// PG 에서는 승인됐지만 우리 서버의 확인 응답이 끊긴 상황 → 승인 통지로 확정, 반복돼도 1번
		sandbox.confirm(paymentKey, orderId, 3000);
		notify(paymentKey, orderId).andExpect(jsonPath("$.result").value("confirmed"));
		notify(paymentKey, orderId).andExpect(jsonPath("$.result").value("already-processed"));
		notify(paymentKey, orderId).andExpect(jsonPath("$.result").value("already-processed"));
		expectTotal(3000, 1);

		// 뒤늦게 브라우저가 승인 확인을 보내도 중복 적립 없음
		confirm(donor, orderId, paymentKey, 3000).andExpect(status().isOk());
		expectTotal(3000, 1);
	}

	@Test
	void forgedNotificationDoesNotConfirm() throws Exception {
		String orderId = newOrder(2000);
		// PG 에 없는 결제키로 통지를 위조 → PG 조회에서 걸러져 무시
		notify("sbx_forged", orderId).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ignored"));
		expectTotal(0, 0);
	}

	@Test
	void tamperedAmountIsRejected() throws Exception {
		// 결제창에서 100원만 결제하고 successUrl 의 amount 도 100 으로 바꾼 경우
		String orderId = newOrder(10000);
		String paymentKey = payInCheckout(orderId, 100);
		confirm(donor, orderId, paymentKey, 100)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("결제 금액이 후원 요청 금액과 달라 후원을 확정하지 않았습니다."));
		mvc.perform(get("/api/donations/" + orderId).session(donor.session()))
			.andExpect(jsonPath("$.donation.status").value("failed"));

		// amount 만 원래 금액으로 맞춰 보내도 PG 결제 금액(100원)과 달라 PG 가 승인을 거절
		String orderId2 = newOrder(10000);
		String paymentKey2 = payInCheckout(orderId2, 100);
		confirm(donor, orderId2, paymentKey2, 10000).andExpect(status().isBadRequest());
		expectTotal(0, 0);
	}

	@Test
	void failedAndCanceledPaymentsAreNotCounted() throws Exception {
		String failed = newOrder(5000);
		mvc.perform(post("/api/donations/" + failed + "/fail").with(csrf()).session(donor.session())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"REJECT_CARD_PAYMENT\",\"message\":\"한도초과 혹은 잔액부족\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.donation.status").value("failed"));

		String canceled = newOrder(5000);
		mvc.perform(post("/api/donations/" + canceled + "/fail").with(csrf()).session(donor.session())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"PAY_PROCESS_CANCELED\",\"message\":\"사용자에 의해 결제가 취소되었습니다.\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.donation.status").value("canceled"));

		// 취소된 주문은 나중에 결제키가 와도 확정 불가
		String paymentKey = payInCheckout(canceled, 5000);
		confirm(donor, canceled, paymentKey, 5000).andExpect(status().isConflict());
		expectTotal(0, 0);
	}

	@Test
	void onlyLoggedInDonorCanRequestAndConfirm() throws Exception {
		requestDonation(null, 5000).andExpect(status().isUnauthorized());
		requestDonation(owner, 5000)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("자기 방송에는 후원할 수 없습니다."));
		requestDonation(donor, 500).andExpect(status().isBadRequest());
		requestDonation(donor, 10_000_000).andExpect(status().isBadRequest());

		String orderId = newOrder(5000);
		String paymentKey = payInCheckout(orderId, 5000);
		TestUsers.Login stranger = TestUsers.signup(mvc, "stranger");
		confirm(stranger, orderId, paymentKey, 5000).andExpect(status().isForbidden());
		mvc.perform(post("/api/donations/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"orderId\":\"" + orderId + "\",\"paymentKey\":\"" + paymentKey + "\",\"amount\":5000}"))
			.andExpect(status().isUnauthorized());
		expectTotal(0, 0);
	}

}
