package com.example.pickyourfood.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class RecommendationControllerTest {

	// five Korean foods tie on this answer: 김치찌개, 순두부찌개, 육개장, 해장국, 김치볶음밥
	private static final String TIED = """
			{"situation":"ALONE","mood":"DOWN","category":"KOREAN","hunger":"MODERATE","taste":"SPICY"}""";

	@Autowired
	MockMvc mvc;

	@Autowired
	SessionRepository<? extends Session> sessions;

	@Autowired
	JdbcClient jdbc;

	private ResultActions postRecommendation(String body, Cookie... session) throws Exception {
		var request = post("/api/recommendations").contentType(MediaType.APPLICATION_JSON).content(body);
		return mvc.perform(session.length == 0 ? request : request.cookie(session));
	}

	// a logged-in session in the session table, the way a finished Kakao login leaves it
	private Cookie loggedIn(long accountId) {
		jdbc.sql("merge into account (id, kakao_id, nickname, created_at) key (id) values (?, ?, ?, ?)")
				.params(accountId, "test-" + accountId, "사용자", Timestamp.from(Instant.now())).update();
		var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
				Map.of("accountId", accountId, "nickname", "사용자"), "accountId");
		String id = store(sessions, new SecurityContextImpl(new OAuth2AuthenticationToken(user, user.getAuthorities(), "kakao")));
		return new Cookie("SESSION", Base64.getEncoder().encodeToString(id.getBytes()));
	}

	private static <S extends Session> String store(SessionRepository<S> repository, SecurityContextImpl context) {
		S session = repository.createSession();
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
		repository.save(session);
		return session.getId();
	}

	private void saved(long accountId, String foodName) {
		jdbc.sql("insert into saved_result (id, account_id, title, food_name, payload, created_at) values (?, ?, ?, ?, ?, ?)")
				.params("r" + System.nanoTime(), accountId, "저장", foodName, "{}", Timestamp.from(Instant.now())).update();
	}

	private Set<String> bestsOf(int times, Cookie... session) throws Exception {
		Set<String> bests = new HashSet<>();
		for (int i = 0; i < times; i++) {
			String body = postRecommendation(TIED, session).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			bests.add(JsonPath.read(body, "$.best.id"));
		}
		return bests;
	}

	@Test
	void aFoodTheUserSavedWinsATie() throws Exception {
		Cookie session = loggedIn(2001);
		saved(2001, "해장국");

		assertThat(bestsOf(15, session)).containsExactly("haejangguk");
	}

	@Test
	void visitorsAndOtherUsersGetNoBonus() throws Exception {
		loggedIn(2002);
		saved(2002, "해장국");

		assertThat(bestsOf(30)).hasSizeGreaterThan(1);
		assertThat(bestsOf(30, loggedIn(2003))).hasSizeGreaterThan(1);
	}

	@Test
	void recommendationReturnsBestAndTwoAlternatives() throws Exception {
		postRecommendation("""
				{"situation":"ALONE","mood":"DOWN","category":"ANY","hunger":"MODERATE","taste":"SPICY"}""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.best.name").isString())
				.andExpect(jsonPath("$.best.category").isString())
				.andExpect(jsonPath("$.alternatives.length()").value(2));
	}

	@Test
	void missingAnswerIsBadRequest() throws Exception {
		postRecommendation("""
				{"situation":"ALONE","category":"ANY","hunger":"MODERATE","taste":"SPICY"}""")
				.andExpect(status().isBadRequest());
	}

	@Test
	void unknownOrLowercaseValueIsBadRequest() throws Exception {
		postRecommendation("""
				{"situation":"alone","mood":"DOWN","category":"ANY","hunger":"MODERATE","taste":"SPICY"}""")
				.andExpect(status().isBadRequest());
		postRecommendation("""
				{"situation":"ALONE","mood":"HAPPY","category":"ANY","hunger":"MODERATE","taste":"SPICY"}""")
				.andExpect(status().isBadRequest());
	}

	@Test
	void emptyOrMalformedBodyIsBadRequest() throws Exception {
		postRecommendation("").andExpect(status().isBadRequest());
		postRecommendation("{oops").andExpect(status().isBadRequest());
	}
}
