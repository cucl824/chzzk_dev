package dev.chzzk.vod.user;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 로그인한 사용자 정보. 세션의 SecurityContext 에 저장되며 컨트롤러에서 {@code @AuthenticationPrincipal} 로 받는다.
 * 비로그인 요청이면 컨트롤러 인자는 null 이다.
 */
public class LoginUser implements UserDetails, CredentialsContainer {

	private final Long id;
	private final String username;
	private final String nickname;
	private String passwordHash;

	public LoginUser(User user) {
		this.id = user.getId();
		this.username = user.getUsername();
		this.nickname = user.getNickname();
		this.passwordHash = user.getPasswordHash();
	}

	public Long getId() {
		return id;
	}

	public String getNickname() {
		return nickname;
	}

	@Override
	public String getUsername() {
		return username;
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_USER"));
	}

	/** 로그인 후 세션에 비밀번호 해시가 남지 않도록 지운다. */
	@Override
	public void eraseCredentials() {
		passwordHash = null;
	}

}
