package dev.chzzk.vod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.jayway.jsonpath.JsonPath;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 라이브 시작·시그널링 중계·종료·녹화본·자동 종료(2단계)와 소유자 권한(3단계).
 * 실제 포트로 띄워 로그인 세션 쿠키를 가진 HTTP·WebSocket 으로 접속한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveApiTests {

	private static final AtomicInteger SEQ = new AtomicInteger();

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
	ObjectMapper mapper;

	private HttpApi user(String name) throws Exception {
		return HttpApi.signedUp(port, name + SEQ.incrementAndGet());
	}

	/** 받은 메시지를 큐에 쌓아두는 테스트용 WebSocket 클라이언트 (로그인 세션 쿠키를 넣을 수 있음) */
	class Client extends TextWebSocketHandler {
		final BlockingQueue<Map<String, Object>> inbox = new LinkedBlockingQueue<>();
		WebSocketSession session;

		Client(String liveId, HttpApi as) throws Exception {
			// 브라우저처럼 큰 메시지를 받을 수 있도록 테스트 클라이언트 버퍼도 늘린다.
			WebSocketContainer container = ContainerProvider.getWebSocketContainer();
			container.setDefaultMaxTextMessageBufferSize(256 * 1024);
			WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
			if (as != null) {
				headers.add("Cookie", as.cookieHeader());
			}
			session = new StandardWebSocketClient(container)
				.execute(this, headers, java.net.URI.create("ws://localhost:" + port + "/ws/lives/" + liveId))
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

	private String[] startLive(HttpApi owner, String title) throws Exception {
		HttpApi.Res res = owner.postJson("/api/lives", "{\"title\":\"" + title + "\"}");
		assertEquals(201, res.status(), res.body());
		assertEquals("live", JsonPath.read(res.body(), "$.live.status"));
		return new String[] { JsonPath.read(res.body(), "$.live.id"), JsonPath.read(res.body(), "$.publisherToken") };
	}

	@Test
	void liveFlowRelaysSignalingAndEnds() throws Exception {
		HttpApi owner = user("owner");
		String[] started = startLive(owner, "휴대폰 라이브");
		String id = started[0];
		String token = started[1];

		Client publisher = new Client(id, owner);
		publisher.send(Map.of("type", "publish", "token", token));
		publisher.await("published");

		// 시청자는 비로그인도 가능
		Client viewer = new Client(id, null);
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

		HttpApi anonymous = new HttpApi(port);
		assertEquals(1, (int) JsonPath.read(anonymous.get("/api/lives/" + id).body(), "$.live.viewerCount"));
		assertEquals(id, JsonPath.read(anonymous.get("/api/lives?status=live").body(), "$.lives[0].id"));

		endLive(owner, id);
		viewer.await("ended");
		publisher.await("ended");

		// 종료 후 소유자의 녹화본 → 소유자의 다시보기로 등록되고 라이브와 연결
		HttpApi.Res rec = owner.postFile("/api/lives/" + id + "/recording", "video", "live.webm", "video/webm",
				new byte[500]);
		assertEquals(200, rec.status(), rec.body());
		String vodId = JsonPath.read(rec.body(), "$.live.vodBroadcastId");
		assertNotNull(vodId);
		String vod = anonymous.get("/api/broadcasts/" + vodId).body();
		assertEquals("휴대폰 라이브", JsonPath.read(vod, "$.broadcast.title"));
		assertEquals((Object) JsonPath.read(rec.body(), "$.live.owner.id"), JsonPath.read(vod, "$.broadcast.owner.id"));
		assertEquals(409, owner.postFile("/api/lives/" + id + "/recording", "video", "live.webm", "video/webm",
				new byte[500]).status());

		// 종료된 방송에 들어온 시청자는 바로 ended 를 받는다
		Client late = new Client(id, null);
		late.send(Map.of("type", "watch"));
		late.await("ended");
	}

	private static void endLive(HttpApi owner, String id) throws Exception {
		HttpApi.Res end = owner.post("/api/lives/" + id + "/end");
		assertEquals(200, end.status(), end.body());
		assertEquals("ended", JsonPath.read(end.body(), "$.live.status"));
	}

	@Test
	void onlyOwnerControlsBroadcast() throws Exception {
		HttpApi owner = user("owner");
		HttpApi other = user("other");
		HttpApi anonymous = new HttpApi(port);

		// 비로그인은 방송을 시작할 수 없다
		assertEquals(401, anonymous.postJson("/api/lives", "{\"title\":\"익명\"}").status());

		String[] started = startLive(owner, "소유자 방송");
		String id = started[0];
		String token = started[1];

		// 송출 연결: 비로그인·다른 사용자는 토큰을 알아도 거절, 소유자만 허용
		Client anonPublisher = new Client(id, null);
		anonPublisher.send(Map.of("type", "publish", "token", token));
		anonPublisher.await("error");
		Client otherPublisher = new Client(id, other);
		otherPublisher.send(Map.of("type", "publish", "token", token));
		otherPublisher.await("error");
		Client wrongToken = new Client(id, owner);
		wrongToken.send(Map.of("type", "publish", "token", "wrong"));
		wrongToken.await("error");
		Client publisher = new Client(id, owner);
		publisher.send(Map.of("type", "publish", "token", token));
		publisher.await("published");

		// 제목 수정·종료·삭제: 비로그인 401, 다른 사용자 403
		assertEquals(401, anonymous.patchJson("/api/lives/" + id, "{\"title\":\"x\"}").status());
		assertEquals(403, other.patchJson("/api/lives/" + id, "{\"title\":\"x\"}").status());
		assertEquals(401, anonymous.post("/api/lives/" + id + "/end").status());
		HttpApi.Res denied = other.post("/api/lives/" + id + "/end");
		assertEquals(403, denied.status());
		assertEquals("이 방송의 소유자만 송출을 제어할 수 있습니다.", JsonPath.read(denied.body(), "$.error"));
		assertEquals("live", JsonPath.read(anonymous.get("/api/lives/" + id).body(), "$.live.status"));

		// 소유자: 제목 수정 → 방송 중 삭제는 409 → 종료 → 삭제
		HttpApi.Res renamed = owner.patchJson("/api/lives/" + id, "{\"title\":\"바뀐 제목\"}");
		assertEquals("바뀐 제목", JsonPath.read(renamed.body(), "$.live.title"));
		assertEquals(409, owner.delete("/api/lives/" + id).status());
		endLive(owner, id);
		assertEquals(403, other.delete("/api/lives/" + id).status());
		assertEquals(403, other.postFile("/api/lives/" + id + "/recording", "video", "x.webm", "video/webm",
				new byte[10]).status());

		// 라이브 댓글: 비로그인 401, 로그인 201, 목록은 공개
		assertEquals(401, anonymous.postJson("/api/lives/" + id + "/comments", "{\"content\":\"hi\"}").status());
		assertEquals(201, other.postJson("/api/lives/" + id + "/comments", "{\"content\":\"잘 봤어요\"}").status());
		assertEquals("잘 봤어요", JsonPath.read(anonymous.get("/api/lives/" + id + "/comments").body(),
				"$.comments[0].content"));

		assertEquals(200, owner.delete("/api/lives/" + id).status());
		assertEquals(404, anonymous.get("/api/lives/" + id).status());
	}

	@Test
	void liveEndsWhenPublisherNeverConnects() throws Exception {
		HttpApi owner = user("owner");
		String id = startLive(owner, "연결 안 함")[0];
		long deadline = System.currentTimeMillis() + 8000;
		while (System.currentTimeMillis() < deadline) {
			if ("ended".equals(JsonPath.read(owner.get("/api/lives/" + id).body(), "$.live.status"))) {
				return;
			}
			Thread.sleep(300);
		}
		fail("유예 시간이 지나도 방송이 종료되지 않음");
	}

	@Test
	void rejectsInvalidStart() throws Exception {
		HttpApi owner = user("owner");
		HttpApi.Res res = owner.postJson("/api/lives", "{\"title\":\"  \"}");
		assertEquals(400, res.status());
		assertEquals("방송 제목을 입력하세요.", JsonPath.read(res.body(), "$.error"));
		assertEquals(404, owner.get("/api/lives/nope").status());
	}

	@Test
	void exposesIceServers() throws Exception {
		assertEquals("stun:stun.l.google.com:19302",
				JsonPath.read(new HttpApi(port).get("/api/rtc-config").body(), "$.iceServers[0].urls[0]"));
	}

}
