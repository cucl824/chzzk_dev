package dev.chzzk.vod.common;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import dev.chzzk.vod.payment.PaymentException;

/** 모든 오류를 화면이 읽는 {"error": "..."} 형식으로 돌려준다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private final long maxUploadMb;

	public GlobalExceptionHandler(@Value("${app.max-upload-mb}") long maxUploadMb) {
		this.maxUploadMb = maxUploadMb;
	}

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<Map<String, String>> handleApi(ApiException e) {
		return error(e.getStatus(), e.getMessage());
	}

	@ExceptionHandler(PaymentException.class)
	public ResponseEntity<Map<String, String>> handlePayment(PaymentException e) {
		return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "code", e.getCode()));
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<Map<String, String>> handleTooLarge(MaxUploadSizeExceededException e) {
		return error(HttpStatus.BAD_REQUEST, "파일은 " + maxUploadMb + "MB 이하만 업로드할 수 있습니다.");
	}

	@ExceptionHandler({ MultipartException.class, HttpMediaTypeNotSupportedException.class,
			HttpMessageNotReadableException.class })
	public ResponseEntity<Map<String, String>> handleBadForm(Exception e) {
		return error(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다.");
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<Map<String, String>> handleNotFound(NoResourceFoundException e) {
		return error(HttpStatus.NOT_FOUND, "존재하지 않는 경로입니다.");
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
		log.error("처리되지 않은 오류", e);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");
	}

	private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(Map.of("error", message));
	}

}
