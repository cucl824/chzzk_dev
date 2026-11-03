package dev.chzzk.vod;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import dev.chzzk.vod.broadcast.BroadcastRepository;

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

	@BeforeEach
	void clean() {
		repository.deleteAll();
	}

	private static MockMultipartFile video() {
		return new MockMultipartFile("video", "녹화.mp4", "video/mp4", new byte[1000]);
	}

	@Test
	void uploadThenListDetailAndPlay() throws Exception {
		String body = mvc.perform(multipart("/api/broadcasts").file(video()).param("title", "파일 방송"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.broadcast.sourceType").value("upload"))
			.andExpect(jsonPath("$.broadcast.fileName").value("녹화.mp4"))
			.andExpect(jsonPath("$.broadcast.videoUrl", startsWith("/media/")))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(body, "$.broadcast.id");
		String videoUrl = JsonPath.read(body, "$.broadcast.videoUrl");

		mvc.perform(get("/api/broadcasts"))
			.andExpect(jsonPath("$.broadcasts", hasSize(1)))
			.andExpect(jsonPath("$.broadcasts[0].title").value("파일 방송"));
		mvc.perform(get("/api/broadcasts/" + id)).andExpect(jsonPath("$.broadcast.id").value(id));
		mvc.perform(get(videoUrl).header("Range", "bytes=0-99"))
			.andExpect(status().isPartialContent())
			.andExpect(header().string("Content-Length", "100"));
		mvc.perform(post("/api/broadcasts/" + id + "/views")).andExpect(jsonPath("$.viewCount").value(1));
	}

	@Test
	void registerByUrl() throws Exception {
		mvc.perform(multipart("/api/broadcasts").param("title", "URL 방송")
			.param("videoUrl", "https://example.com/v.mp4"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.broadcast.sourceType").value("url"))
			.andExpect(jsonPath("$.broadcast.videoUrl").value("https://example.com/v.mp4"));
	}

	@Test
	void rejectsInvalidInput() throws Exception {
		mvc.perform(multipart("/api/broadcasts").param("videoUrl", "https://example.com/v.mp4"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("제목을 입력하세요."));
		mvc.perform(multipart("/api/broadcasts").param("title", "t"))
			.andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/broadcasts").param("title", "t").param("videoUrl", "ftp://x/v.mp4"))
			.andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/broadcasts").file(video()).param("title", "t")
			.param("videoUrl", "https://example.com/v.mp4"))
			.andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/broadcasts")
			.file(new MockMultipartFile("video", "a.txt", "text/plain", "hi".getBytes()))
			.param("title", "t"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("영상 파일(video/*)만 업로드할 수 있습니다."));

		try (var files = Files.list(tempDir.resolve("uploads"))) {
			org.junit.jupiter.api.Assertions.assertEquals(0, files.count(), "거부된 요청은 파일을 남기지 않는다");
		}
	}

	@Test
	void unknownBroadcastIs404() throws Exception {
		mvc.perform(get("/api/broadcasts/nope"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("방송을 찾을 수 없습니다."));
	}

}
