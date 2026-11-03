package dev.chzzk.vod.broadcast;

import com.fasterxml.jackson.annotation.JsonValue;

/** 영상 위치를 얻은 방식. */
public enum SourceType {
	/** 서버에 업로드된 파일 (/media/...) */
	UPLOAD,
	/** 이미 저장된 외부 영상 URL 연결 */
	URL;

	@JsonValue
	public String json() {
		return name().toLowerCase();
	}
}
