package dev.chzzk.vod.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 회원. 비밀번호는 BCrypt 해시로만 저장한다. */
@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 20)
	private String username;

	@Column(nullable = false, length = 100)
	private String passwordHash;

	@Column(nullable = false, length = 20)
	private String nickname;

	@Column(nullable = false)
	private Instant createdAt;

	protected User() {
	}

	public User(String username, String passwordHash, String nickname) {
		this.username = username;
		this.passwordHash = passwordHash;
		this.nickname = nickname;
		this.createdAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getUsername() {
		return username;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getNickname() {
		return nickname;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
