package dev.chzzk.vod.comment;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import dev.chzzk.vod.user.User;

/** 방송(녹화 방송 또는 라이브)에 달린 댓글. 작성자는 로그인한 회원이다. */
@Entity
@Table(name = "comments", indexes = @Index(name = "idx_comments_target", columnList = "targetType, targetId"))
public class Comment {

	public static final int CONTENT_MAX = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private CommentTarget targetType;

	@Column(nullable = false, length = 36)
	private String targetId;

	@ManyToOne(optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private User author;

	@Column(nullable = false, length = CONTENT_MAX)
	private String content;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant updatedAt;

	protected Comment() {
	}

	public Comment(CommentTarget targetType, String targetId, User author, String content) {
		this.targetType = targetType;
		this.targetId = targetId;
		this.author = author;
		this.content = content;
		this.createdAt = Instant.now();
	}

	public boolean isWrittenBy(Long userId) {
		return author.getId().equals(userId);
	}

	public void edit(String content) {
		this.content = content;
		this.updatedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public CommentTarget getTargetType() {
		return targetType;
	}

	public String getTargetId() {
		return targetId;
	}

	public User getAuthor() {
		return author;
	}

	public String getContent() {
		return content;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
