package dev.chzzk.vod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 실제 포트로 띄운 서버에 브라우저처럼 접속하는 테스트 클라이언트.
 * 세션 쿠키(JSESSIONID)와 CSRF 쿠키(XSRF-TOKEN)를 보관하고, 변경 요청에 X-XSRF-TOKEN 헤더를 붙인다.
 */
final class HttpApi {

	record Res(int status, String body) {
	}

	private final String base;
	private final CookieManager cookies = new CookieManager();
	private final HttpClient client;

	HttpApi(int port) throws Exception {
		this.base = "http://localhost:" + port;
		this.client = HttpClient.newBuilder().cookieHandler(cookies).build();
		get("/"); // CSRF 쿠키 받기
	}

	/** 새 사용자로 가입(=로그인)한 클라이언트 */
	static HttpApi signedUp(int port, String username) throws Exception {
		HttpApi api = new HttpApi(port);
		Res res = api.postJson("/api/auth/signup",
				"{\"username\":\"" + username + "\",\"password\":\"password123\",\"nickname\":\"" + username + "\"}");
		if (res.status() != 201) {
			throw new IllegalStateException("가입 실패: " + res);
		}
		return api;
	}

	Res get(String path) throws Exception {
		return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
	}

	Res postJson(String path, String json) throws Exception {
		return send(withCsrf(HttpRequest.newBuilder(URI.create(base + path)))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json)));
	}

	Res post(String path) throws Exception {
		return send(withCsrf(HttpRequest.newBuilder(URI.create(base + path))).POST(HttpRequest.BodyPublishers.noBody()));
	}

	Res patchJson(String path, String json) throws Exception {
		return send(withCsrf(HttpRequest.newBuilder(URI.create(base + path)))
			.header("Content-Type", "application/json")
			.method("PATCH", HttpRequest.BodyPublishers.ofString(json)));
	}

	Res delete(String path) throws Exception {
		return send(withCsrf(HttpRequest.newBuilder(URI.create(base + path))).DELETE());
	}

	Res postFile(String path, String field, String fileName, String contentType, byte[] data) throws Exception {
		String boundary = "----test" + System.nanoTime();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\""
				+ fileName + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(data);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return send(withCsrf(HttpRequest.newBuilder(URI.create(base + path)))
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
	}

	/** WebSocket 핸드셰이크에 넣을 Cookie 헤더 (로그인 세션 포함) */
	String cookieHeader() {
		return cookies.getCookieStore()
			.get(URI.create(base))
			.stream()
			.map(c -> c.getName() + "=" + c.getValue())
			.collect(Collectors.joining("; "));
	}

	private HttpRequest.Builder withCsrf(HttpRequest.Builder builder) {
		cookies.getCookieStore()
			.get(URI.create(base))
			.stream()
			.filter(c -> c.getName().equals("XSRF-TOKEN"))
			.map(HttpCookie::getValue)
			.findFirst()
			.ifPresent(token -> builder.header("X-XSRF-TOKEN", token));
		return builder;
	}

	private Res send(HttpRequest.Builder builder) throws IOException, InterruptedException {
		HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		return new Res(res.statusCode(), res.body());
	}

}
