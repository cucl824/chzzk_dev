package dev.chzzk.vod.live;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 라이브 방송 메타데이터. 영상은 저장하지 않고 송출자 브라우저에서 시청자에게 WebRTC로 바로 전달된다.
 * 종료 후 송출자가 녹화본을 올리면 다시보기(Broadcast)와 연결된다.
 */
@Entity
@Table(name = "live_streams")
public class LiveStream {

	public static final int TITLE_MAX = 100;

	@Id
	@Column(length = 36)
	private String id;

	@Column(nullable = false, length = TITLE_MAX)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private LiveStatus status;

	/**
	 * 방송을 시작한 브라우저만 송출·종료할 수 있도록 발급하는 임의 값. 로그인이 아니라 "이 방송의 송출 화면"임을
	 * 구분하는 용도이며, 응답으로는 시작할 때 한 번만 내려준다.
	 */
	@Column(nullable = false, length = 64)
	private String publisherToken;

	@Column(nullable = false)
	private Instant startedAt;

	private Instant endedAt;

	/** 종료 후 녹화본이 등록되면 해당 다시보기의 id */
	@Column(length = 36)
	private String vodBroadcastId;

	protected LiveStream() {
	}

	public LiveStream(String title, String publisherToken) {
		this.id = UUID.randomUUID().toString();
		this.title = title;
		this.publisherToken = publisherToken;
		this.status = LiveStatus.LIVE;
		this.startedAt = Instant.now();
	}

	public boolean isLive() {
		return status == LiveStatus.LIVE;
	}

	public void end() {
		if (isLive()) {
			status = LiveStatus.ENDED;
			endedAt = Instant.now();
		}
	}

	public void linkVod(String broadcastId) {
		this.vodBroadcastId = broadcastId;
	}

	public String getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public LiveStatus getStatus() {
		return status;
	}

	public String getPublisherToken() {
		return publisherToken;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	public String getVodBroadcastId() {
		return vodBroadcastId;
	}

}
