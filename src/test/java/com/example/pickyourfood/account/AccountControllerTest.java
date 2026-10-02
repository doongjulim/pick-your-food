package com.example.pickyourfood.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AccountControllerTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	SessionRepository<? extends Session> sessions;

	@MockitoBean
	KakaoUnlink unlink;

	@Autowired
	AccountService accounts;

	@Autowired
	JdbcClient jdbc;

	private void saveResult(String id, long accountId) {
		jdbc.sql("insert into saved_result (id, account_id, title, food_name, payload, created_at) "
				+ "values (?, ?, '오늘의 랜덤 메뉴', '탄탄멘', '{}', current_timestamp)").params(id, accountId).update();
	}

	private long count(String sql, long id) {
		return jdbc.sql(sql).param(id).query(Long.class).single();
	}

	// DELETE /api/me with a valid CSRF token, as the frontend sends it
	private ResultActions deleteMe(Cookie session) throws Exception {
		Cookie xsrf = xsrfCookie();
		return mvc.perform(delete("/api/me").cookie(session, xsrf).header("X-XSRF-TOKEN", xsrf.getValue()));
	}

	// stores a logged-in session in the session table, the way a finished Kakao login leaves it
	private String loggedIn(long id, String nickname) {
		var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
				Map.of(AccountService.ACCOUNT_ID, id, AccountService.NICKNAME, nickname), AccountService.ACCOUNT_ID);
		return store(sessions, new SecurityContextImpl(new OAuth2AuthenticationToken(user, user.getAuthorities(), "kakao")));
	}

	private static <S extends Session> String store(SessionRepository<S> repository, SecurityContextImpl context) {
		S session = repository.createSession();
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
		repository.save(session);
		return session.getId();
	}

	private static Cookie cookieFor(String sessionId) {
		return new Cookie("SESSION", Base64.getEncoder().encodeToString(sessionId.getBytes()));
	}

	// the XSRF-TOKEN cookie any response carries, sent back as the header the way the frontend does
	private Cookie xsrfCookie() throws Exception {
		return mvc.perform(get("/api/foods/random")).andReturn().getResponse().getCookie("XSRF-TOKEN");
	}

	@Test
	void meWithoutLoginIsUnauthorized() throws Exception {
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void meReturnsTheLoggedInAccount() throws Exception {
		mvc.perform(get("/api/me").cookie(cookieFor(loggedIn(7, "서윤"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(7))
				.andExpect(jsonPath("$.nickname").value("서윤"));
	}

	@Test
	void otherApiPathsNeedLoginAndAnswer401InsteadOfRedirecting() throws Exception {
		mvc.perform(get("/api/saved")).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutWithoutCsrfTokenIsForbidden() throws Exception {
		String id = loggedIn(8, "하람");

		mvc.perform(post("/api/logout").cookie(cookieFor(id))).andExpect(status().isForbidden());

		assertThat(sessions.findById(id)).isNotNull();
	}

	@Test
	void logoutDeletesTheSession() throws Exception {
		String id = loggedIn(9, "도윤");

		Cookie xsrf = xsrfCookie();

		mvc.perform(post("/api/logout").cookie(cookieFor(id), xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
				.andExpect(status().isNoContent());

		assertThat(sessions.findById(id)).isNull();
		mvc.perform(get("/api/me").cookie(cookieFor(id))).andExpect(status().isUnauthorized());
	}

	@Test
	void kakaoLoginRedirectsToKakaoAskingOnlyForTheNickname() throws Exception {
		mvc.perform(get("/oauth2/authorization/kakao"))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location", allOf(
						startsWith("https://kauth.kakao.com/oauth/authorize?"),
						containsString("scope=profile_nickname"),
						containsString("redirect_uri=http://localhost/login/oauth2/code/kakao"))));
	}

	@Test
	void pagesGetTheCsrfCookieForTheFrontend() throws Exception {
		mvc.perform(get("/api/foods/random")).andExpect(cookie().exists("XSRF-TOKEN"));
	}

	@Test
	void sessionsLastThirtyDaysInAnHttpOnlyLaxCookie() throws Exception {
		assertThat(sessions.createSession().getMaxInactiveInterval()).isEqualTo(Duration.ofDays(30));

		// starting a login is the first response that opens a session
		mvc.perform(get("/oauth2/authorization/kakao"))
				.andExpect(cookie().maxAge("SESSION", (int) Duration.ofDays(30).toSeconds()))
				.andExpect(cookie().httpOnly("SESSION", true))
				.andExpect(cookie().sameSite("SESSION", "Lax"));
	}

	@Test
	void deletingTheAccountRemovesItsDataAndEverySession() throws Exception {
		Account me = accounts.signIn("delete-me", "서윤");
		Account other = accounts.signIn("delete-other", "하람");
		saveResult("del-mine", me.id());
		saveResult("del-other", other.id());
		Cookie phone = cookieFor(loggedIn(me.id(), "서윤"));
		Cookie laptop = cookieFor(loggedIn(me.id(), "서윤"));

		deleteMe(phone).andExpect(status().isNoContent());

		verify(unlink).unlink("delete-me");
		assertThat(count("select count(*) from account where id = ?", me.id())).isZero();
		assertThat(count("select count(*) from saved_result where account_id = ?", me.id())).isZero();
		assertThat(count("select count(*) from saved_result where account_id = ?", other.id())).isEqualTo(1);
		mvc.perform(get("/api/me").cookie(phone)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me").cookie(laptop)).andExpect(status().isUnauthorized());
	}

	@Test
	void failedKakaoUnlinkDeletesNothing() throws Exception {
		Account me = accounts.signIn("delete-fails", "서윤");
		saveResult("del-fails", me.id());
		Cookie session = cookieFor(loggedIn(me.id(), "서윤"));
		doThrow(new ResourceAccessException("Kakao is down")).when(unlink).unlink(anyString());

		deleteMe(session).andExpect(status().isBadGateway());

		assertThat(count("select count(*) from account where id = ?", me.id())).isEqualTo(1);
		assertThat(count("select count(*) from saved_result where account_id = ?", me.id())).isEqualTo(1);
		mvc.perform(get("/api/me").cookie(session)).andExpect(status().isOk());
	}

	@Test
	void deletingNeedsTheCsrfToken() throws Exception {
		Account me = accounts.signIn("delete-no-csrf", "서윤");
		Cookie session = cookieFor(loggedIn(me.id(), "서윤"));

		mvc.perform(delete("/api/me").cookie(session)).andExpect(status().isForbidden());

		verifyNoInteractions(unlink);
		assertThat(count("select count(*) from account where id = ?", me.id())).isEqualTo(1);
	}

	@Test
	void deletingNeedsALogin() throws Exception {
		Cookie xsrf = xsrfCookie();

		mvc.perform(delete("/api/me").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(unlink);
	}

	@Test
	void alreadyDeletedAccountJustEndsTheSession() throws Exception {
		Cookie session = cookieFor(loggedIn(987654, "서윤"));

		deleteMe(session).andExpect(status().isNoContent());

		verifyNoInteractions(unlink);
		mvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
	}
}
