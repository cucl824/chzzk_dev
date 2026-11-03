package dev.chzzk.vod;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

/** 회원가입·로그인·로그아웃과 댓글 작성자 권한 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthAndCommentTests {

	@TempDir
	static Path tempDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("app.upload-dir", () -> tempDir.resolve("uploads").toString());
		registry.add("spring.datasource.url", () -> "jdbc:h2:mem:auth;DB_CLOSE_DELAY=-1");
	}

	@Autowired
	MockMvc mvc;

	private static String json(String username, String password, String nickname) {
		return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"nickname\":\"" + nickname + "\"}";
	}

	@Test
	void signupLoginMeLogout() throws Exception {
		mvc.perform(get("/api/auth/me")).andExpect(jsonPath("$.user", nullValue()));
		// 첫 응답부터 CSRF 토큰 쿠키가 내려간다 (화면 스크립트가 X-XSRF-TOKEN 헤더로 보냄)
		mvc.perform(get("/")).andExpect(cookie().exists("XSRF-TOKEN"));

		mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(json("alice", "password123", "앨리스")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.user.nickname").value("앨리스"));
		mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(json("alice", "password123", "중복")))
			.andExpect(status().isConflict());
		mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(json("bob", "short", "밥")))
			.andExpect(status().isBadRequest());

		mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"alice\",\"password\":\"wrong-password\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("아이디 또는 비밀번호가 올바르지 않습니다."));
		MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"alice\",\"password\":\"password123\"}"))
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);

		mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.user.nickname").value("앨리스"));
		mvc.perform(post("/api/auth/logout").with(csrf()).session(session)).andExpect(status().isOk());
		mvc.perform(get("/api/auth/me").session(session)).andExpect(jsonPath("$.user", nullValue()));
	}

	@Test
	void commentsArePublicToReadButNeedLoginToWriteAndAuthorToEdit() throws Exception {
		TestUsers.Login owner = TestUsers.signup(mvc, "owner");
		TestUsers.Login writer = TestUsers.signup(mvc, "writer");
		TestUsers.Login other = TestUsers.signup(mvc, "other");
		String body = mvc.perform(multipart("/api/broadcasts").with(csrf()).session(owner.session())
			.param("title", "댓글 테스트").param("videoUrl", "https://example.com/v.mp4"))
			.andReturn().getResponse().getContentAsString();
		String broadcastId = JsonPath.read(body, "$.broadcast.id");
		String url = "/api/broadcasts/" + broadcastId + "/comments";

		// 비로그인 작성 → 401
		mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"익명\"}"))
			.andExpect(status().isUnauthorized());

		// 로그인 작성 → 작성자는 세션의 사용자 (본문에 다른 작성자를 넣어도 무시)
		String created = mvc.perform(post(url).with(csrf()).session(writer.session())
			.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"첫 댓글\",\"author\":{\"id\":999}}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.comment.author.id").value(writer.id()))
			.andReturn().getResponse().getContentAsString();
		Number commentId = JsonPath.read(created, "$.comment.id");
		String commentUrl = "/api/comments/" + commentId;

		mvc.perform(post(url).with(csrf()).session(writer.session()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"   \"}"))
			.andExpect(status().isBadRequest());

		// 목록은 비로그인도 조회
		mvc.perform(get(url)).andExpect(jsonPath("$.comments", hasSize(1)))
			.andExpect(jsonPath("$.comments[0].content").value("첫 댓글"));
		mvc.perform(get("/api/broadcasts/nope/comments")).andExpect(status().isNotFound());

		// 수정·삭제: 비로그인 401, 다른 사용자(방송 소유자 포함) 403, 작성자 200
		String edit = "{\"content\":\"고친 댓글\"}";
		mvc.perform(patch(commentUrl).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(edit))
			.andExpect(status().isUnauthorized());
		mvc.perform(patch(commentUrl).with(csrf()).session(other.session()).contentType(MediaType.APPLICATION_JSON)
			.content(edit))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("댓글 작성자만 수정·삭제할 수 있습니다."));
		mvc.perform(delete(commentUrl).with(csrf()).session(owner.session())).andExpect(status().isForbidden());
		mvc.perform(patch(commentUrl).with(csrf()).session(writer.session()).contentType(MediaType.APPLICATION_JSON)
			.content(edit))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.comment.content").value("고친 댓글"))
			.andExpect(jsonPath("$.comment.updatedAt").isNotEmpty());
		mvc.perform(delete(commentUrl).with(csrf()).session(writer.session())).andExpect(status().isOk());
		mvc.perform(get(url)).andExpect(jsonPath("$.comments", hasSize(0)));

		// 방송이 삭제되면 댓글도 함께 삭제
		mvc.perform(post(url).with(csrf()).session(writer.session()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"남은 댓글\"}"))
			.andExpect(status().isCreated());
		mvc.perform(delete("/api/broadcasts/" + broadcastId).with(csrf()).session(owner.session()))
			.andExpect(status().isOk());
		mvc.perform(get(url)).andExpect(status().isNotFound());
	}

}
