package dev.chzzk.vod.config;

import java.io.IOException;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import dev.chzzk.vod.user.UserService;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증·인가 설정.
 *
 * <ul>
 * <li>조회(GET)·시청·WebSocket 시청은 누구나 가능</li>
 * <li>/api/** 의 등록·수정·삭제·송출 제어·댓글 작성은 로그인 필요 (비로그인 → 401)</li>
 * <li>"내 방송/내 댓글인지"는 각 서비스가 DB 의 소유자·작성자와 비교해 검사 (다른 사용자 → 403)</li>
 * </ul>
 * 세션 쿠키 기반이므로 CSRF 토큰(XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더)을 검사한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
		CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
		// 토큰을 매 요청 읽게 해서 첫 화면 응답부터 XSRF-TOKEN 쿠키가 내려가도록 한다.
		csrfHandler.setCsrfRequestAttributeName(null);

		http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
			.csrfTokenRequestHandler(csrfHandler))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.GET, "/**").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/logout").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/broadcasts/*/views").permitAll()
				.requestMatchers("/api/**").authenticated()
				.anyRequest().permitAll())
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint((request, response, e) -> writeError(mapper, response, 401, "로그인이 필요합니다."))
				.accessDeniedHandler((request, response, e) -> writeError(mapper, response, 403,
						e instanceof CsrfException ? "보안 토큰이 없거나 만료되었습니다. 새로고침한 뒤 다시 시도하세요."
								: "권한이 없습니다.")))
			.logout(logout -> logout.logoutUrl("/api/auth/logout")
				.logoutSuccessHandler((request, response, auth) -> {
					response.setContentType(MediaType.APPLICATION_JSON_VALUE);
					response.setCharacterEncoding("UTF-8");
					mapper.writeValue(response.getWriter(), Map.of("ok", true));
				}))
			.formLogin(form -> form.disable())
			.httpBasic(basic -> basic.disable());
		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public AuthenticationManager authenticationManager(UserService userService, PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userService);
		provider.setPasswordEncoder(passwordEncoder);
		return new ProviderManager(provider);
	}

	@Bean
	public SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	private static void writeError(ObjectMapper mapper, HttpServletResponse response, int status, String message)
			throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		mapper.writeValue(response.getWriter(), Map.of("error", message));
	}

}
