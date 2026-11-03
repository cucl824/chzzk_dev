package dev.chzzk.vod;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import dev.chzzk.vod.broadcast.BroadcastRepository;

/** 녹화 방송 등록·조회·재생(1단계)과 소유자 권한(3단계) */
@SpringBootTest
@AutoConfigureMockMvc
class BroadcastApiTests {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("app.upload-dir", () -> tempDir.resolve("uploads").toString());
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	BroadcastRepository repository;

	TestUsers.Login owner;

	@BeforeEach
	void setUp() throws Exception {
		repository.deleteAll();
		owner = TestUsers.signup(mvc, "owner");
	}

	private static MockMultipartFile video() {
		return new MockMultipartFile("video", "녹화.mp4", "video/mp4", new byte[1000]);
	}

	private String createUrlBroadcast() throws Exception {
		String body = mvc.perform(multipart("/api/broadcasts").with(csrf()).session(owner.session())
			.param("title", "URL 방송").param("videoUrl", "https://example.com/v.mp4"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.broadcast.id");
	}

	@Test
	void uploadThenListDetailAndPlay() throws Exception {
		String body = mvc.perform(multipart("/api/broadcasts").file(video()).param("title", "파일 방송")
			.with(csrf()).session(owner.session()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.broadcast.sourceType").value("upload"))
			.andExpect(jsonPath("$.broadcast.fileName").value("녹화.mp4"))
			.andExpect(jsonPath("$.broadcast.videoUrl", startsWith("/media/")))
			.andExpect(jsonPath("$.broadcast.owner.id").value(owner.id()))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(body, "$.broadcast.id");
		String videoUrl = JsonPath.read(body, "$.broadcast.videoUrl");

		// 목록·상세·재생·조회수는 비로그인도 가능
		mvc.perform(get("/api/broadcasts"))
			.andExpect(jsonPath("$.broadcasts", hasSize(1)))
			.andExpect(jsonPath("$.broadcasts[0].title").value("파일 방송"));
		mvc.perform(get("/api/broadcasts/" + id)).andExpect(jsonPath("$.broadcast.id").value(id));
		mvc.perform(get(videoUrl).header("Range", "bytes=0-99"))
			.andExpect(status().isPartialContent())
			.andExpect(header().string("Content-Length", "100"));
		mvc.perform(post("/api/broadcasts/" + id + "/views").with(csrf())).andExpect(jsonPath("$.viewCount").value(1));
	}

	@Test
	void registerByUrl() throws Exception {
		mvc.perform(multipart("/api/broadcasts").with(csrf()).session(owner.session()).param("title", "URL 방송")
			.param("videoUrl", "https://example.com/v.mp4"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.broadcast.sourceType").value("url"))
			.andExpect(jsonPath("$.broadcast.videoUrl").value("https://example.com/v.mp4"));
	}

	@Test
	void rejectsInvalidInput() throws Exception {
		mvc.perform(multipart("/api/broadcasts").with(csrf()).session(owner.session())
			.param("videoUrl", "https://example.com/v.mp4"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("제목을 입력하세요."));
		mvc.perform(multipart("/api/broadcasts").with(csrf()).session(owner.session()).param("title", "t"))
			.andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/broadcasts").with(csrf()).session(owner.session()).param("title", "t")
			.param("videoUrl", "ftp://x/v.mp4"))
			.andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/broadcasts").file(video()).with(csrf()).session(owner.session()).param("title", "t")
			.param("videoUrl", "https://example.com/v.mp4"))
			.andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/broadcasts").with(csrf()).session(owner.session())
			.file(new MockMultipartFile("video", "a.txt", "text/plain", "hi".getBytes()))
			.param("title", "t"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("영상 파일(video/*)만 업로드할 수 있습니다."));

		try (var files = Files.list(tempDir.resolve("uploads"))) {
			Assertions.assertEquals(0, files.count(), "거부된 요청은 파일을 남기지 않는다");
		}
	}

	@Test
	void unknownBroadcastIs404() throws Exception {
		mvc.perform(get("/api/broadcasts/nope"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("방송을 찾을 수 없습니다."));
	}

	@Test
	void anonymousCannotRegister() throws Exception {
		mvc.perform(multipart("/api/broadcasts").with(csrf()).param("title", "t")
			.param("videoUrl", "https://example.com/v.mp4"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("로그인이 필요합니다."));
		mvc.perform(get("/api/broadcasts")).andExpect(jsonPath("$.broadcasts", hasSize(0)));
	}

	@Test
	void requestWithoutCsrfTokenIsRejected() throws Exception {
		mvc.perform(multipart("/api/broadcasts").session(owner.session()).param("title", "t")
			.param("videoUrl", "https://example.com/v.mp4"))
			.andExpect(status().isForbidden());
	}

	@Test
	void onlyOwnerCanUpdateAndDelete() throws Exception {
		String id = createUrlBroadcast();
		TestUsers.Login other = TestUsers.signup(mvc, "other");
		String update = "{\"title\":\"바뀐 제목\",\"description\":\"설명\"}";

		// 비로그인 → 401
		mvc.perform(patch("/api/broadcasts/" + id).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(update))
			.andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/broadcasts/" + id).with(csrf())).andExpect(status().isUnauthorized());
		// 다른 사용자 → 403
		mvc.perform(patch("/api/broadcasts/" + id).with(csrf()).session(other.session())
			.contentType(MediaType.APPLICATION_JSON).content(update))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("이 방송의 소유자만 수정·삭제할 수 있습니다."));
		mvc.perform(delete("/api/broadcasts/" + id).with(csrf()).session(other.session()))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/broadcasts/" + id)).andExpect(jsonPath("$.broadcast.title").value("URL 방송"));

		// 소유자 → 허용
		mvc.perform(patch("/api/broadcasts/" + id).with(csrf()).session(owner.session())
			.contentType(MediaType.APPLICATION_JSON).content(update))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.broadcast.title").value("바뀐 제목"));
		mvc.perform(delete("/api/broadcasts/" + id).with(csrf()).session(owner.session())).andExpect(status().isOk());
		mvc.perform(get("/api/broadcasts/" + id)).andExpect(status().isNotFound());
	}

}
