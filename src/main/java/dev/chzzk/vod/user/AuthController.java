package dev.chzzk.vod.user;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.chzzk.vod.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 회원가입·로그인·내 정보. 세션(JSESSIONID 쿠키) 기반이며 로그아웃은 SecurityConfig 의 POST /api/auth/logout 이 처리한다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final UserService userService;
	private final AuthenticationManager authenticationManager;
	private final SecurityContextRepository securityContextRepository;

	public AuthController(UserService userService, AuthenticationManager authenticationManager,
			SecurityContextRepository securityContextRepository) {
		this.userService = userService;
		this.authenticationManager = authenticationManager;
		this.securityContextRepository = securityContextRepository;
	}

	public record SignupRequest(String username, String password, String nickname) {
	}

	public record LoginRequest(String username, String password) {
	}

	/** 가입 후 바로 로그인 상태가 된다. */
	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, UserSummary> signup(@RequestBody SignupRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		userService.signup(body.username(), body.password(), body.nickname());
		return Map.of("user", login(body.username().trim(), body.password(), request, response));
	}

	@PostMapping("/login")
	public Map<String, UserSummary> login(@RequestBody LoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		if (body.username() == null || body.password() == null) {
			throw ApiException.badRequest("아이디와 비밀번호를 입력하세요.");
		}
		return Map.of("user", login(body.username().trim(), body.password(), request, response));
	}

	/** 로그인 상태 확인. 비로그인이면 {"user": null} */
	@GetMapping("/me")
	public Map<String, UserSummary> me(@AuthenticationPrincipal LoginUser me) {
		Map<String, UserSummary> body = new HashMap<>();
		body.put("user", UserSummary.of(me));
		return body;
	}

	private UserSummary login(String username, String password, HttpServletRequest request,
			HttpServletResponse response) {
		Authentication authentication;
		try {
			authentication = authenticationManager
				.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
		}
		catch (AuthenticationException e) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.");
		}
		// 세션 고정 공격을 막기 위해 로그인할 때 세션 id 를 새로 발급한다.
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
		return UserSummary.of((LoginUser) authentication.getPrincipal());
	}

}
