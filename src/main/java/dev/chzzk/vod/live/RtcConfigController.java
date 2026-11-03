package dev.chzzk.vod.live;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브라우저 RTCPeerConnection 에 넘길 ICE 서버 설정.
 * 같은 Wi-Fi 안에서는 STUN 만으로 충분하고, 휴대폰 LTE ↔ 다른 망처럼 직접 연결이 막히는 환경은 TURN 서버가 필요하다.
 */
@RestController
public class RtcConfigController {

	private final Map<String, Object> config;

	public RtcConfigController(@Value("${app.rtc.stun-urls}") String stunUrls,
			@Value("${app.rtc.turn-url}") String turnUrl,
			@Value("${app.rtc.turn-username}") String turnUsername,
			@Value("${app.rtc.turn-credential}") String turnCredential) {
		List<Map<String, Object>> iceServers = new ArrayList<>();
		List<String> stun = Arrays.stream(stunUrls.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
		if (!stun.isEmpty()) {
			iceServers.add(Map.of("urls", stun));
		}
		if (!turnUrl.isBlank()) {
			iceServers.add(Map.of("urls", turnUrl.trim(), "username", turnUsername, "credential", turnCredential));
		}
		this.config = Map.of("iceServers", iceServers);
	}

	@GetMapping("/api/rtc-config")
	public Map<String, Object> rtcConfig() {
		return config;
	}

}
