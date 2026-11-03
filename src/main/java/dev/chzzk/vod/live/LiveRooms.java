package dev.chzzk.vod.live;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;

/**
 * 방송 중인 라이브마다 "방"을 메모리에 두고 WebRTC 시그널링 메시지를 중계한다.
 *
 * <p>영상은 서버를 거치지 않는다. 송출자(휴대폰)가 시청자마다 RTCPeerConnection 을 하나씩 만들고,
 * 서버는 그 연결을 맺는 데 필요한 offer/answer/ICE 메시지만 전달한다.
 *
 * <pre>
 * 시청자 접속 → 송출자에게 viewer-joined
 * 송출자 offer(to=시청자) → 시청자
 * 시청자 answer → 송출자(from=시청자)
 * 양쪽 ice 후보 교환 → P2P 연결 완료
 * </pre>
 */
@Component
public class LiveRooms {

	private static final Logger log = LoggerFactory.getLogger(LiveRooms.class);

	private final Map<String, Room> rooms = new ConcurrentHashMap<>();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "live-publisher-grace");
		t.setDaemon(true);
		return t;
	});
	private final SecureRandom random = new SecureRandom();
	private final ObjectMapper mapper;
	private final ApplicationEventPublisher events;
	private final int maxViewers;
	private final long graceSeconds;

	public LiveRooms(ObjectMapper mapper, ApplicationEventPublisher events,
			@Value("${app.live.max-viewers}") int maxViewers,
			@Value("${app.live.publisher-grace-seconds}") long graceSeconds) {
		this.mapper = mapper;
		this.events = events;
		this.maxViewers = maxViewers;
		this.graceSeconds = graceSeconds;
	}

	private static final class Room {
		final String liveId;
		WebSocketSession publisher;
		final Map<String, WebSocketSession> viewers = new LinkedHashMap<>();
		ScheduledFuture<?> graceTimer;

		Room(String liveId) {
			this.liveId = liveId;
		}
	}

	/** 방송 시작 시 방을 연다. 송출자가 유예 시간 안에 접속하지 않으면 자동 종료된다. */
	public void open(String liveId) {
		Room room = new Room(liveId);
		rooms.put(liveId, room);
		synchronized (room) {
			startGraceTimer(room);
		}
	}

	public int viewerCount(String liveId) {
		Room room = rooms.get(liveId);
		if (room == null) {
			return 0;
		}
		synchronized (room) {
			return room.viewers.size();
		}
	}

	/** 송출자 연결. 이미 연결된 송출 화면이 있으면 새 연결로 교체한다(재접속). */
	public void attachPublisher(String liveId, WebSocketSession session) {
		Room room = rooms.get(liveId);
		if (room == null) {
			sendAndClose(session, Map.of("type", "ended"));
			return;
		}
		synchronized (room) {
			if (room.publisher != null && !room.publisher.getId().equals(session.getId())) {
				closeQuietly(room.publisher);
			}
			room.publisher = session;
			if (room.graceTimer != null) {
				room.graceTimer.cancel(false);
				room.graceTimer = null;
			}
			send(session, Map.of("type", "published", "viewers", room.viewers.size()));
			// 재접속한 송출자는 기존 시청자 모두와 연결을 다시 맺는다.
			for (String viewerId : room.viewers.keySet()) {
				send(session, Map.of("type", "viewer-joined", "viewerId", viewerId));
			}
			broadcastToViewers(room, Map.of("type", "publisher-back"));
		}
	}

	/** 시청자 연결. 시청자 id 를 발급해 세션 속성(viewerId)에 기록한다. */
	public void attachViewer(String liveId, WebSocketSession session) {
		Room room = rooms.get(liveId);
		if (room == null) {
			sendAndClose(session, Map.of("type", "ended"));
			return;
		}
		synchronized (room) {
			if (room.viewers.size() >= maxViewers) {
				sendAndClose(session, Map.of("type", "full", "max", maxViewers));
				return;
			}
			String viewerId = newViewerId();
			session.getAttributes().put(LiveSignalingHandler.ATTR_VIEWER_ID, viewerId);
			room.viewers.put(viewerId, session);
			send(session, Map.of("type", "welcome", "viewerId", viewerId, "publisherOnline", room.publisher != null));
			if (room.publisher != null) {
				send(room.publisher, Map.of("type", "viewer-joined", "viewerId", viewerId));
			}
			broadcastViewerCount(room);
		}
	}

	/** 송출자 → 특정 시청자(to)에게 offer / ice 전달 */
	public void relayFromPublisher(String liveId, WebSocketSession session, Map<String, Object> message) {
		Room room = rooms.get(liveId);
		if (room == null) {
			return;
		}
		synchronized (room) {
			if (room.publisher == null || !room.publisher.getId().equals(session.getId())) {
				return;
			}
			Object to = message.remove("to");
			WebSocketSession viewer = to == null ? null : room.viewers.get(to.toString());
			if (viewer != null) {
				send(viewer, message);
			}
		}
	}

	/** 시청자 → 송출자에게 answer / ice 전달 (from 에 시청자 id 를 붙인다) */
	public void relayFromViewer(String liveId, String viewerId, Map<String, Object> message) {
		Room room = rooms.get(liveId);
		if (room == null) {
			return;
		}
		synchronized (room) {
			if (room.publisher != null && room.viewers.containsKey(viewerId)) {
				message.put("from", viewerId);
				send(room.publisher, message);
			}
		}
	}

	/** 연결 종료 처리. 송출자가 끊기면 유예 시간 동안 재접속을 기다린다. */
	public void detach(String liveId, WebSocketSession session) {
		Room room = rooms.get(liveId);
		if (room == null) {
			return;
		}
		synchronized (room) {
			if (room.publisher != null && room.publisher.getId().equals(session.getId())) {
				room.publisher = null;
				broadcastToViewers(room, Map.of("type", "publisher-left"));
				startGraceTimer(room);
				return;
			}
			Object viewerId = session.getAttributes().get(LiveSignalingHandler.ATTR_VIEWER_ID);
			if (viewerId != null && room.viewers.remove(viewerId.toString()) != null) {
				if (room.publisher != null) {
					send(room.publisher, Map.of("type", "viewer-left", "viewerId", viewerId));
				}
				broadcastViewerCount(room);
			}
		}
	}

	/** 방송 종료. 모든 참여자에게 ended 를 보내고 연결을 닫는다. */
	public void close(String liveId) {
		Room room = rooms.remove(liveId);
		if (room == null) {
			return;
		}
		synchronized (room) {
			if (room.graceTimer != null) {
				room.graceTimer.cancel(false);
			}
			Map<String, Object> ended = Map.of("type", "ended");
			if (room.publisher != null) {
				sendAndClose(room.publisher, ended);
			}
			for (WebSocketSession viewer : List.copyOf(room.viewers.values())) {
				sendAndClose(viewer, ended);
			}
			room.viewers.clear();
		}
	}

	@PreDestroy
	void shutdown() {
		scheduler.shutdownNow();
	}

	private void startGraceTimer(Room room) {
		if (room.graceTimer != null) {
			room.graceTimer.cancel(false);
		}
		room.graceTimer = scheduler.schedule(() -> {
			log.info("송출자 연결이 {}초 동안 없어 라이브 {} 를 종료합니다.", graceSeconds, room.liveId);
			events.publishEvent(new PublisherTimedOutEvent(room.liveId));
		}, graceSeconds, TimeUnit.SECONDS);
	}

	private void broadcastViewerCount(Room room) {
		Map<String, Object> count = Map.of("type", "viewers", "count", room.viewers.size());
		broadcastToViewers(room, count);
		if (room.publisher != null) {
			send(room.publisher, count);
		}
	}

	private void broadcastToViewers(Room room, Map<String, Object> message) {
		for (WebSocketSession viewer : room.viewers.values()) {
			send(viewer, message);
		}
	}

	private String newViewerId() {
		byte[] bytes = new byte[6];
		random.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	private void send(WebSocketSession session, Map<String, ?> message) {
		if (!session.isOpen()) {
			return;
		}
		try {
			session.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
		}
		catch (IOException | RuntimeException e) {
			log.debug("시그널링 메시지 전송 실패: {}", e.getMessage());
		}
	}

	private void sendAndClose(WebSocketSession session, Map<String, ?> message) {
		send(session, message);
		closeQuietly(session);
	}

	private static void closeQuietly(WebSocketSession session) {
		try {
			session.close(CloseStatus.NORMAL);
		}
		catch (IOException ignored) {
			// 이미 닫힌 연결
		}
	}

}
