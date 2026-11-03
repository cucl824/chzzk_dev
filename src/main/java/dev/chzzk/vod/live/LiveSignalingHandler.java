package dev.chzzk.vod.live;

import java.net.URI;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import dev.chzzk.vod.user.LoginUser;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * /ws/lives/{liveId} WebSocket. 첫 메시지로 역할을 정한다.
 * <ul>
 * <li>{"type":"publish","token":"..."} — 송출자 (방송 시작 때 받은 송출 토큰 필요)</li>
 * <li>{"type":"watch"} — 시청자 (로그인 없이 누구나)</li>
 * </ul>
 * 이후 offer / answer / ice 메시지를 {@link LiveRooms} 가 상대에게 중계한다.
 */
@Component
public class LiveSignalingHandler extends TextWebSocketHandler {

	static final String ATTR_VIEWER_ID = "viewerId";
	private static final String ATTR_ROLE = "role";
	private static final String ATTR_OUT = "out";
	private static final String ROLE_PUBLISHER = "publisher";
	private static final String ROLE_VIEWER = "viewer";

	private static final TypeReference<Map<String, Object>> MESSAGE_TYPE = new TypeReference<>() {
	};

	private final LiveRooms rooms;
	private final LiveService liveService;
	private final ObjectMapper mapper;

	public LiveSignalingHandler(LiveRooms rooms, LiveService liveService, ObjectMapper mapper) {
		this.rooms = rooms;
		this.liveService = liveService;
		this.mapper = mapper;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		// WebSocketSession.sendMessage 는 동시 호출에 안전하지 않으므로 감싼 세션으로만 보낸다.
		session.getAttributes().put(ATTR_OUT, new ConcurrentWebSocketSessionDecorator(session, 5_000, 512 * 1024));
	}

	@Override
	protected void handleTextMessage(WebSocketSession raw, TextMessage text) {
		WebSocketSession session = out(raw);
		String liveId = liveId(raw);
		Map<String, Object> message;
		try {
			message = mapper.readValue(text.getPayload(), MESSAGE_TYPE);
		}
		catch (JacksonException e) {
			return;
		}
		String type = String.valueOf(message.get("type"));
		Object role = raw.getAttributes().get(ATTR_ROLE);

		if (role == null) {
			if ("publish".equals(type)) {
				// 핸드셰이크 요청의 로그인 세션으로 송출자를 확인한다. (소유자 + 시작한 화면의 토큰)
				if (liveService.isPublisher(liveId, loginUserId(raw), String.valueOf(message.get("token")))) {
					raw.getAttributes().put(ATTR_ROLE, ROLE_PUBLISHER);
					rooms.attachPublisher(liveId, session);
				}
				else {
					rejectAndClose(session, "이 방송의 소유자로 로그인한 송출 화면만 송출할 수 있습니다.");
				}
			}
			else if ("watch".equals(type)) {
				raw.getAttributes().put(ATTR_ROLE, ROLE_VIEWER);
				rooms.attachViewer(liveId, session);
			}
			return;
		}

		if (!"offer".equals(type) && !"answer".equals(type) && !"ice".equals(type)) {
			return;
		}
		if (ROLE_PUBLISHER.equals(role)) {
			rooms.relayFromPublisher(liveId, session, message);
		}
		else {
			Object viewerId = raw.getAttributes().get(ATTR_VIEWER_ID);
			if (viewerId != null) {
				rooms.relayFromViewer(liveId, viewerId.toString(), message);
			}
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
		if (raw.getAttributes().get(ATTR_ROLE) != null) {
			rooms.detach(liveId(raw), out(raw));
		}
	}

	private void rejectAndClose(WebSocketSession session, String reason) {
		try {
			session.sendMessage(new TextMessage(mapper.writeValueAsString(Map.of("type", "error", "error", reason))));
			session.close(CloseStatus.POLICY_VIOLATION);
		}
		catch (Exception ignored) {
			// 연결이 이미 닫힘
		}
	}

	private static Long loginUserId(WebSocketSession session) {
		return session.getPrincipal() instanceof Authentication auth && auth.getPrincipal() instanceof LoginUser user
				? user.getId() : null;
	}

	private static WebSocketSession out(WebSocketSession raw) {
		Object out = raw.getAttributes().get(ATTR_OUT);
		return out instanceof WebSocketSession decorated ? decorated : raw;
	}

	private static String liveId(WebSocketSession session) {
		URI uri = session.getUri();
		String path = uri == null ? "" : uri.getPath();
		return path.substring(path.lastIndexOf('/') + 1);
	}

}
