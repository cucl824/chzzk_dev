package dev.chzzk.vod.common;

import org.springframework.http.HttpStatus;

/** 사용자에게 그대로 보여줄 메시지를 담는 예외. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	public ApiException(HttpStatus status, String message) {
		super(message);
		this.status = status;
	}

	public static ApiException badRequest(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, message);
	}

	public static ApiException notFound(String message) {
		return new ApiException(HttpStatus.NOT_FOUND, message);
	}

	public HttpStatus getStatus() {
		return status;
	}

}
