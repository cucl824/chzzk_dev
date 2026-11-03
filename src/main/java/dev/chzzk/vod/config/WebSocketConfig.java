package dev.chzzk.vod.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import dev.chzzk.vod.live.LiveSignalingHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final LiveSignalingHandler signalingHandler;

	public WebSocketConfig(LiveSignalingHandler signalingHandler) {
		this.signalingHandler = signalingHandler;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		// 로그인이 없는 단계이고 쿠키 인증도 쓰지 않으므로, 터널(https 주소) 등 다른 Origin 에서도 접속을 허용한다.
		// SDP(offer/answer)가 기본 메시지 버퍼(8KB)보다 클 수 있어 application.yml 에서 버퍼를 늘린다.
		registry.addHandler(signalingHandler, "/ws/lives/*").setAllowedOriginPatterns("*");
	}

}
