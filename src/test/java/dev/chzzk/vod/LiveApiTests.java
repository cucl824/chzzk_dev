package dev.chzzk.vod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.jayway.jsonpath.JsonPath;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 라이브 시작·시그널링 중계·종료·녹화본 등록·자동 종료 통합 테스트 (실제 포트로 WebSocket 접속) */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class LiveApiTests {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("app.upload-dir", () -> tempDir.resolve("uploads").toString());
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:live;DB_CLOSE_DELAY=-1");
		registry.add("app.live.publisher-grace-seconds", () -> "2");
	}

	@Value("${local.server.port}")
	int port;

	@Autowired
	MockMvc mvc;

	@Autowired
	ObjectMapper mapper;

	/** 받은 메시지를 큐에 쌓아두는 테스트용 WebSocket 클라이언트 */
	class Client extends TextWebSocketHandler {
		final BlockingQueue<Map<String, Object>> inbox = new LinkedBlockingQueue<>();
		WebSocketSession session;

		Client(String liveId) throws Exception {
			// 브라우저처럼 큰 메시지를 받을 수 있도록 테스트 클라이언트 버퍼도 늘린다.
			WebSocketContainer container = ContainerProvider.getWebSocketContainer();
			container.setDefaultMaxTextMessageBufferSize(256 * 1024);
			session = new StandardWebSocketClient(container)
				.execute(this, "ws://localhost:" + port + "/ws/lives/" + liveId)
				.get(5, TimeUnit.SECONDS);
		}

		@Override
		protected void handleTextMessage(WebSocketSession s, TextMessage message) {
			inbox.add(mapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() {
			}));
		}

		void send(Map<String, Object> message) throws Exception {
			session.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
		}

		/** 원하는 type 의 메시지가 올 때까지 기다린다. (viewers 같은 다른 메시지는 건너뜀) */
		Map<String, Object> await(String type) throws InterruptedException {
			long deadline = System.currentTimeMillis() + 5000;
			while (System.currentTimeMillis() < deadline) {
				Map<String, Object> message = inbox.poll(200, TimeUnit.MILLISECONDS);
				if (message != null && type.equals(message.get("type"))) {
					return message;
				}
			}
			return fail("메시지를 받지 못함: " + type);
		}
	}

	private String[] startLive(String title) throws Exception {
		String body = mvc.perform(post("/api/lives").contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"" + title + "\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.live.status").value("live"))
			.andReturn().getResponse().getContentAsString();
		return new String[] { JsonPath.read(body, "$.live.id"), JsonPath.read(body, "$.publisherToken") };
	}

	@Test
	void liveFlowRelaysSignalingAndEnds() throws Exception {
		String[] started = startLive("휴대폰 라이브");
		String id = started[0];
		String token = started[1];

		Client publisher = new Client(id);
		publisher.send(Map.of("type", "publish", "token", token));
		publisher.await("published");

		Client viewer = new Client(id);
		viewer.send(Map.of("type", "watch"));
		String viewerId = (String) viewer.await("welcome").get("viewerId");
		assertEquals(viewerId, publisher.await("viewer-joined").get("viewerId"));

		// 송출자 offer → 해당 시청자에게만 전달 (to 는 제거됨). 기본 버퍼(8KB)보다 큰 SDP 도 통과해야 한다.
		String bigSdp = "v=0\r\n" + "a=x\r\n".repeat(4000);
		publisher.send(Map.of("type", "offer", "to", viewerId, "sdp", Map.of("type", "offer", "sdp", bigSdp)));
		Map<String, Object> offer = viewer.await("offer");
		assertEquals(Map.of("type", "offer", "sdp", bigSdp), offer.get("sdp"));
		assertFalse(offer.containsKey("to"));

		// 시청자 answer / ice → 송출자에게 from 과 함께 전달
		viewer.send(Map.of("type", "answer", "sdp", Map.of("type", "answer", "sdp", "v=0")));
		assertEquals(viewerId, publisher.await("answer").get("from"));
		viewer.send(Map.of("type", "ice", "candidate", Map.of("candidate", "c")));
		assertEquals(viewerId, publisher.await("ice").get("from"));

		mvc.perform(get("/api/lives/" + id)).andExpect(jsonPath("$.live.viewerCount").value(1));
		mvc.perform(get("/api/lives").param("status", "live")).andExpect(jsonPath("$.lives[0].id").value(id));

		// 토큰 없이 종료·송출 불가
		mvc.perform(post("/api/lives/" + id + "/end")).andExpect(status().isForbidden());
		Client intruder = new Client(id);
		intruder.send(Map.of("type", "publish", "token", "wrong"));
		intruder.await("error");

		mvc.perform(post("/api/lives/" + id + "/end").header("X-Publisher-Token", token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.live.status").value("ended"))
			.andExpect(jsonPath("$.live.endedAt").isNotEmpty());
		viewer.await("ended");
		publisher.await("ended");

		// 종료 후 녹화본 → 다시보기로 등록되고 라이브와 연결
		String body = mvc.perform(multipart("/api/lives/" + id + "/recording")
			.file(new MockMultipartFile("video", "live.webm", "video/webm", new byte[500]))
			.header("X-Publisher-Token", token))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String vodId = JsonPath.read(body, "$.live.vodBroadcastId");
		assertNotNull(vodId);
		mvc.perform(get("/api/broadcasts/" + vodId)).andExpect(jsonPath("$.broadcast.title").value("휴대폰 라이브"));
		mvc.perform(multipart("/api/lives/" + id + "/recording")
			.file(new MockMultipartFile("video", "live.webm", "video/webm", new byte[500]))
			.header("X-Publisher-Token", token))
			.andExpect(status().isConflict());

		// 종료된 방송에 들어온 시청자는 바로 ended 를 받는다
		Client late = new Client(id);
		late.send(Map.of("type", "watch"));
		late.await("ended");
	}

	@Test
	void liveEndsWhenPublisherNeverConnects() throws Exception {
		String id = startLive("연결 안 함")[0];
		long deadline = System.currentTimeMillis() + 8000;
		while (System.currentTimeMillis() < deadline) {
			String body = mvc.perform(get("/api/lives/" + id)).andReturn().getResponse().getContentAsString();
			if ("ended".equals(JsonPath.read(body, "$.live.status"))) {
				return;
			}
			Thread.sleep(300);
		}
		fail("유예 시간이 지나도 방송이 종료되지 않음");
	}

	@Test
	void rejectsInvalidStart() throws Exception {
		mvc.perform(post("/api/lives").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("방송 제목을 입력하세요."));
		mvc.perform(get("/api/lives/nope")).andExpect(status().isNotFound());
	}

	@Test
	void exposesIceServers() throws Exception {
		mvc.perform(get("/api/rtc-config"))
			.andExpect(jsonPath("$.iceServers[0].urls[0]").value("stun:stun.l.google.com:19302"));
	}

}
