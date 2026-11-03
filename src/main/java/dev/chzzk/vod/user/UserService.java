package dev.chzzk.vod.user;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.chzzk.vod.common.ApiException;

/** 회원가입과 로그인용 사용자 조회. */
@Service
public class UserService implements UserDetailsService {

	private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,20}$");

	private final UserRepository repository;
	private final PasswordEncoder passwordEncoder;

	public UserService(UserRepository repository, PasswordEncoder passwordEncoder) {
		this.repository = repository;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	public User signup(String rawUsername, String password, String rawNickname) {
		String username = rawUsername == null ? "" : rawUsername.trim();
		String nickname = rawNickname == null ? "" : rawNickname.trim();
		if (!USERNAME.matcher(username).matches()) {
			throw ApiException.badRequest("아이디는 영문·숫자·밑줄(_) 3~20자로 입력하세요.");
		}
		if (password == null || password.length() < 8 || password.length() > 64) {
			throw ApiException.badRequest("비밀번호는 8~64자로 입력하세요.");
		}
		if (nickname.length() < 2 || nickname.length() > 20) {
			throw ApiException.badRequest("닉네임은 2~20자로 입력하세요.");
		}
		if (repository.existsByUsername(username)) {
			throw new ApiException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
		}
		return repository.save(new User(username, passwordEncoder.encode(password), nickname));
	}

	@Override
	@Transactional(readOnly = true)
	public LoginUser loadUserByUsername(String username) {
		return repository.findByUsername(username)
			.map(LoginUser::new)
			.orElseThrow(() -> new UsernameNotFoundException(username));
	}

	/** 로그인 사용자 → 엔티티. 세션은 있는데 회원이 없어진 경우(DB 초기화 등)는 다시 로그인하게 한다. */
	@Transactional(readOnly = true)
	public User require(LoginUser me) {
		if (me == null) {
			throw ApiException.unauthorized();
		}
		return repository.findById(me.getId()).orElseThrow(ApiException::unauthorized);
	}

}
