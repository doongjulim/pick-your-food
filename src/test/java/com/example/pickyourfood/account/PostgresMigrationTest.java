package com.example.pickyourfood.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

// production runs on PostgreSQL (Neon) while everything else tests on H2; skipped when Docker isn't running
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	AccountService accounts;

	@Autowired
	SessionRepository<? extends Session> sessions;

	@Autowired
	JsonMapper json;

	private static <S extends Session> String store(SessionRepository<S> repository, SecurityContextImpl context) {
		S session = repository.createSession();
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
		repository.save(session);
		return session.getId();
	}

	@Test
	void everyMigrationRunsOnPostgres() {
		assertThat(jdbc.sql("select version from flyway_schema_history where success order by installed_rank")
				.query(String.class).list()).containsExactly("1", "2", "3");
	}

	@Test
	void accountsSessionsAndSavedResultsWork() throws Exception {
		Account account = accounts.signIn("pg-1", "서윤");
		assertThat(accounts.signIn("pg-1", "서윤").id()).isEqualTo(account.id());

		// the security context is stored as bytes (BYTEA) and read back on the next request
		var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
				Map.of(AccountService.ACCOUNT_ID, account.id(), AccountService.NICKNAME, account.nickname()), AccountService.ACCOUNT_ID);
		String sessionId = store(sessions, new SecurityContextImpl(new OAuth2AuthenticationToken(user, user.getAuthorities(), "kakao")));
		Cookie session = new Cookie("SESSION", Base64.getEncoder().encodeToString(sessionId.getBytes()));
		mvc.perform(get("/api/me").cookie(session)).andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("서윤"));

		Cookie xsrf = mvc.perform(get("/api/foods/random")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		String body = mvc.perform(post("/api/saved").cookie(session, xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"오늘의 랜덤 메뉴","best":{"id":"tantanmen","name":"탄탄멘","description":"고소하고 매콤한 국물"},"alternatives":[]}"""))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String id = json.readTree(body).get("id").asString();

		mvc.perform(get("/api/saved/" + id).cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mine").value(true))
				.andExpect(jsonPath("$.result.best.name").value("탄탄멘"));
		mvc.perform(get("/api/saved").cookie(session)).andExpect(jsonPath("$[0].id").value(id));
		mvc.perform(delete("/api/saved/" + id).cookie(session, xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/saved/" + id)).andExpect(status().isNotFound());
	}
}
