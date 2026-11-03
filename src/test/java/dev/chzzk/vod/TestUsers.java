package dev.chzzk.vod;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/** MockMvc 테스트용: 회원가입해서 로그인된 세션을 만든다. */
final class TestUsers {

	private static final AtomicInteger SEQ = new AtomicInteger();

	record Login(MockHttpSession session, long id) {
	}

	private TestUsers() {
	}

	static Login signup(MockMvc mvc, String prefix) throws Exception {
		String username = prefix + "_" + SEQ.incrementAndGet();
		MvcResult result = mvc.perform(post("/api/auth/signup").with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"" + username + "\",\"password\":\"password123\",\"nickname\":\"" + prefix + "\"}"))
			.andExpect(status().isCreated())
			.andReturn();
		Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.user.id");
		return new Login((MockHttpSession) result.getRequest().getSession(false), id.longValue());
	}

}
