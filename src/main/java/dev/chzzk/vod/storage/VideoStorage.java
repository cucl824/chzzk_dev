package dev.chzzk.vod.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 업로드 영상을 로컬 디스크에 저장한다. 저장된 파일은 {@link #PUBLIC_PATH} 아래로 제공된다.
 * 다음 단계에서 S3 같은 객체 스토리지로 바꿀 때 이 클래스만 교체하면 된다.
 */
@Component
public class VideoStorage {

	public static final String PUBLIC_PATH = "/media/";

	private final Path root;
	private final SecureRandom random = new SecureRandom();

	public VideoStorage(@Value("${app.upload-dir}") String uploadDir) throws IOException {
		this.root = Path.of(uploadDir).toAbsolutePath().normalize();
		Files.createDirectories(root);
	}

	public Path getRoot() {
		return root;
	}

	/** 파일을 저장하고 재생에 사용할 공개 경로(/media/파일명)를 돌려준다. */
	public String save(MultipartFile file) {
		byte[] suffix = new byte[6];
		random.nextBytes(suffix);
		String storedName = System.currentTimeMillis() + "-" + HexFormat.of().formatHex(suffix)
				+ extension(file.getOriginalFilename());
		try (InputStream in = file.getInputStream()) {
			Files.copy(in, root.resolve(storedName));
		}
		catch (IOException e) {
			throw new UncheckedIOException("영상 파일 저장 실패", e);
		}
		return PUBLIC_PATH + storedName;
	}

	/** 등록이 실패했을 때 이미 저장한 파일을 정리한다. */
	public void deleteQuietly(String publicPath) {
		if (publicPath == null || !publicPath.startsWith(PUBLIC_PATH)) {
			return;
		}
		try {
			Files.deleteIfExists(root.resolve(publicPath.substring(PUBLIC_PATH.length())));
		}
		catch (IOException ignored) {
			// 정리 실패는 등록 오류 응답에 영향을 주지 않는다.
		}
	}

	private static String extension(String originalName) {
		if (originalName == null) {
			return "";
		}
		int dot = originalName.lastIndexOf('.');
		if (dot < 0) {
			return "";
		}
		String ext = originalName.substring(dot).toLowerCase(Locale.ROOT).replaceAll("[^.a-z0-9]", "");
		return ext.length() > 10 ? "" : ext;
	}

}
