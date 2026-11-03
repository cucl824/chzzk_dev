package dev.chzzk.vod.broadcast;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import dev.chzzk.vod.user.User;

/** 녹화 방송 메타데이터. 영상 자체는 파일/외부 URL에 있고 여기에는 위치(videoUrl)만 저장한다. */
@Entity
@Table(name = "broadcasts")
public class Broadcast {

	public static final int TITLE_MAX = 100;
	public static final int DESCRIPTION_MAX = 2000;

	@Id
	@Column(length = 36)
	private String id;

	@Column(nullable = false, length = TITLE_MAX)
	private String title;

	@Column(nullable = false, length = DESCRIPTION_MAX)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private SourceType sourceType;

	/** 재생에 사용할 영상 위치. 업로드면 /media/..., 연결이면 외부 URL. */
	@Column(nullable = false, length = 2048)
	private String videoUrl;

	private String fileName;

	private String mimeType;

	private Long size;

	@Column(nullable = false)
	private long viewCount;

	@Column(nullable = false)
	private Instant createdAt;

	/** 등록한 회원. 1·2단계(로그인 도입 전)에 등록된 방송은 null 이며 아무도 수정·삭제할 수 없다. */
	@ManyToOne
	@JoinColumn(name = "owner_id")
	private User owner;

	protected Broadcast() {
	}

	private Broadcast(User owner, String title, String description, SourceType sourceType, String videoUrl) {
		this.owner = owner;
		this.id = UUID.randomUUID().toString();
		this.title = title;
		this.description = description;
		this.sourceType = sourceType;
		this.videoUrl = videoUrl;
		this.createdAt = Instant.now();
	}

	public static Broadcast ofUpload(User owner, String title, String description, String videoUrl, String fileName,
			String mimeType, long size) {
		Broadcast broadcast = new Broadcast(owner, title, description, SourceType.UPLOAD, videoUrl);
		broadcast.fileName = fileName;
		broadcast.mimeType = mimeType;
		broadcast.size = size;
		return broadcast;
	}

	public static Broadcast ofUrl(User owner, String title, String description, String videoUrl) {
		return new Broadcast(owner, title, description, SourceType.URL, videoUrl);
	}

	public void update(String title, String description) {
		this.title = title;
		this.description = description;
	}

	public boolean isOwnedBy(Long userId) {
		return owner != null && owner.getId().equals(userId);
	}

	public User getOwner() {
		return owner;
	}

	public String getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public SourceType getSourceType() {
		return sourceType;
	}

	public String getVideoUrl() {
		return videoUrl;
	}

	public String getFileName() {
		return fileName;
	}

	public String getMimeType() {
		return mimeType;
	}

	public Long getSize() {
		return size;
	}

	public long getViewCount() {
		return viewCount;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
