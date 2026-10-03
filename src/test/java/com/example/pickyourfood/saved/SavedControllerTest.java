package com.example.pickyourfood.saved;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SavedControllerTest {

	private static final long SEOYUN = 1001;
	private static final long HARAM = 1002;

	private static final String PLACE = """
			{"id":"26338954","name":"탄탄면공방","address":"서울 성동구 연무장길 1","distanceMeters":820,"lat":37.5445,"lng":127.0567,
			"kakaoUrl":"http://place.map.kakao.com/26338954","rating":4.4,"reviewCount":1284,"priceLevel":2,
			"reviews":[{"author":"민지","rating":5,"text":"국물이 진해요","when":"1주 전"}],"googleUrl":"https://maps.google.com/?cid=1"}""";

	private static final String SPOT = """
			{"id":"1","name":"어니언","category":"카페","address":"서울 성동구 1","lat":37.541,"lng":127.051,"kakaoUrl":"http://place.map.kakao.com/1"}""";

	private static final String RESULT = """
			{"title":"오늘의 랜덤 메뉴","best":{"id":"tantanmen","name":"탄탄멘","description":"고소하고 매콤한 국물"},
			"alternatives":[{"id":"ramen","name":"라멘","description":"진한 국물"}],
			"places":{"origin":{"name":"성수동","lat":37.54,"lng":127.05},"nearby":[%1$s],"famous":[%1$s],
			"dateCourses":[{"restaurant":%1$s,"cafe":%2$s,"sight":null,
			"legs":[{"from":"탄탄면공방","to":"어니언","meters":320,"walkMinutes":5}],"routeUrl":"https://map.kakao.com/link/by/walk/a"}]},
			"secret":"unknown fields are dropped"}""".formatted(PLACE, SPOT);

	private static final String MENU_ONLY = """
			{"title":"당신에게 딱 맞는 메뉴","best":{"id":"bibimbap","name":"비빔밥","description":"골고루"},"alternatives":[]}""";

	@Autowired
	MockMvc mvc;

	@Autowired
	SessionRepository<? extends Session> sessions;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	@BeforeEach
	void accounts() {
		for (long id : new long[] { SEOYUN, HARAM }) {
			jdbc.sql("merge into account (id, kakao_id, nickname, created_at) key (id) values (?, ?, ?, ?)")
					.params(id, "test-" + id, "사용자" + id, Timestamp.from(Instant.now())).update();
		}
	}

	// a logged-in session in the session table, the way a finished Kakao login leaves it
	private Cookie loggedIn(long accountId) {
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

	// sends the XSRF-TOKEN cookie back as the header, the way the frontend does
	private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, Cookie... session) throws Exception {
		Cookie xsrf = mvc.perform(get("/api/foods/random")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		for (Cookie cookie : session) request.cookie(cookie);
		return request.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
	}

	private String save(long accountId, String body) throws Exception {
		String response = mvc.perform(withCsrf(post("/api/saved"), loggedIn(accountId))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return json.readTree(response).get("id").asString();
	}

	private void rejects(String body) throws Exception {
		mvc.perform(withCsrf(post("/api/saved"), loggedIn(SEOYUN)).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());
	}

	@Test
	void savingNeedsLogin() throws Exception {
		mvc.perform(withCsrf(post("/api/saved")).contentType(MediaType.APPLICATION_JSON).content(RESULT))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void savingNeedsTheCsrfHeader() throws Exception {
		mvc.perform(post("/api/saved").cookie(loggedIn(SEOYUN)).contentType(MediaType.APPLICATION_JSON).content(RESULT))
				.andExpect(status().isForbidden());
	}

	@Test
	void savedResultGetsAnUnguessableLinkAndDropsGoogleAndUnknownFields() throws Exception {
		String id = save(SEOYUN, RESULT);

		assertThat(id).matches("[A-Za-z0-9_-]{22}");
		mvc.perform(get("/api/saved/" + id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.createdAt").isString())
				.andExpect(jsonPath("$.mine").value(false))
				.andExpect(jsonPath("$.result.best.name").value("탄탄멘"))
				.andExpect(jsonPath("$.result.alternatives[0].name").value("라멘"))
				.andExpect(jsonPath("$.result.places.origin.name").value("성수동"))
				.andExpect(jsonPath("$.result.places.nearby[0].name").value("탄탄면공방"))
				.andExpect(jsonPath("$.result.places.nearby[0].kakaoUrl").value("http://place.map.kakao.com/26338954"))
				.andExpect(jsonPath("$.result.places.nearby[0].rating").doesNotExist())
				.andExpect(jsonPath("$.result.places.nearby[0].reviews").doesNotExist())
				.andExpect(jsonPath("$.result.places.nearby[0].googleUrl").doesNotExist())
				.andExpect(jsonPath("$.result.places.dateCourses[0].restaurant.reviewCount").doesNotExist())
				.andExpect(jsonPath("$.result.places.dateCourses[0].cafe.name").value("어니언"))
				.andExpect(jsonPath("$.result.places.dateCourses[0].legs[0].walkMinutes").value(5))
				.andExpect(jsonPath("$.result.places.dateCourses[0].legs[0].path").isEmpty())
				.andExpect(jsonPath("$.result.secret").doesNotExist());
		assertThat(jdbc.sql("select payload from saved_result where id = ?").param(id).query(String.class).single())
				.doesNotContain("국물이 진해요", "maps.google.com", "unknown fields");
	}

	@Test
	void menuWithoutPlacesCanBeSaved() throws Exception {
		String id = save(SEOYUN, MENU_ONLY);

		mvc.perform(get("/api/saved/" + id))
				.andExpect(jsonPath("$.result.best.name").value("비빔밥"))
				.andExpect(jsonPath("$.result.places").isEmpty());
	}

	@Test
	void malformedOrOversizedResultsAreRejected() throws Exception {
		String food = "{\"id\":\"f\",\"name\":\"메뉴\",\"description\":\"\"}";
		String place = "{\"id\":\"p\",\"name\":\"식당\",\"address\":\"\",\"distanceMeters\":1,\"lat\":0,\"lng\":0,\"kakaoUrl\":\"\"}";
		String course = "{\"restaurant\":" + place + ",\"cafe\":null,\"sight\":null,\"legs\":[],\"routeUrl\":null}";

		rejects("{\"title\":\"t\",\"alternatives\":[]}");
		rejects("{\"best\":" + food + ",\"alternatives\":[]}");
		rejects("{\"title\":\"t\",\"best\":" + food + ",\"alternatives\":[" + repeat(food, 6) + "]}");
		rejects(withPlaces(repeat(place, 11), "", ""));
		rejects(withPlaces("", repeat(place, 11), ""));
		rejects(withPlaces("", "", repeat(course, 4)));
		rejects("{\"title\":\"t\",\"best\":{\"id\":\"f\",\"name\":\"메뉴\",\"description\":\"" + "가".repeat(70_000)
				+ "\"},\"alternatives\":[]}");
	}

	private static String withPlaces(String nearby, String famous, String courses) {
		return "{\"title\":\"t\",\"best\":{\"id\":\"f\",\"name\":\"메뉴\",\"description\":\"\"},\"alternatives\":[],"
				+ "\"places\":{\"origin\":{\"name\":\"성수동\",\"lat\":0,\"lng\":0},\"nearby\":[" + nearby + "],\"famous\":["
				+ famous + "],\"dateCourses\":[" + courses + "]}}";
	}

	private static String repeat(String item, int times) {
		return String.join(",", java.util.Collections.nCopies(times, item));
	}

	@Test
	void walkingPathsAreKeptAndCheckedOnSave() throws Exception {
		String leg = "{\"from\":\"a\",\"to\":\"b\",\"meters\":1,\"walkMinutes\":1,\"path\":[%s]}";
		String id = save(SEOYUN, withLegs(leg.formatted("[37.54,127.05],[37.541,127.051]")));

		mvc.perform(get("/api/saved/" + id))
				.andExpect(jsonPath("$.result.places.dateCourses[0].legs[0].path[1][0]").value(37.541))
				.andExpect(jsonPath("$.result.places.dateCourses[0].legs[0].path[1][1]").value(127.051));

		save(SEOYUN, withLegs(leg.formatted(repeat("[37.54,127.05]", 200))));
		rejects(withLegs(leg.formatted(repeat("[37.54,127.05]", 201))));
		rejects(withLegs(leg.formatted("[91,127.05]")));
		rejects(withLegs(leg.formatted("[-91,127.05]")));
		rejects(withLegs(leg.formatted("[37.54,181]")));
		rejects(withLegs(leg.formatted("[37.54,127.05,0]")));
		rejects(withLegs(leg.formatted("[37.54]")));
		rejects(withLegs(leg.formatted("[37.54,127.05],null")));
	}

	private static String withLegs(String legs) {
		String place = "{\"id\":\"p\",\"name\":\"식당\",\"address\":\"\",\"distanceMeters\":1,\"lat\":0,\"lng\":0,\"kakaoUrl\":\"\"}";
		return withPlaces("", "", "{\"restaurant\":" + place + ",\"cafe\":null,\"sight\":null,\"legs\":[" + legs + "],\"routeUrl\":null}");
	}

	@Test
	void theOwnerSeesTheirResultAsMine() throws Exception {
		String id = save(SEOYUN, MENU_ONLY);

		mvc.perform(get("/api/saved/" + id).cookie(loggedIn(SEOYUN))).andExpect(jsonPath("$.mine").value(true));
		mvc.perform(get("/api/saved/" + id).cookie(loggedIn(HARAM))).andExpect(jsonPath("$.mine").value(false));
	}

	@Test
	void unknownLinkIsNotFound() throws Exception {
		mvc.perform(get("/api/saved/AAAAAAAAAAAAAAAAAAAAAA")).andExpect(status().isNotFound());
	}

	@Test
	void listShowsOnlyMyResultsNewestFirst() throws Exception {
		jdbc.sql("delete from saved_result").update();
		String older = save(SEOYUN, RESULT);
		String newer = save(SEOYUN, MENU_ONLY);
		save(HARAM, MENU_ONLY);

		mvc.perform(get("/api/saved").cookie(loggedIn(SEOYUN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].id").value(newer))
				.andExpect(jsonPath("$[0].foodName").value("비빔밥"))
				.andExpect(jsonPath("$[0].originName").isEmpty())
				.andExpect(jsonPath("$[1].id").value(older))
				.andExpect(jsonPath("$[1].title").value("오늘의 랜덤 메뉴"))
				.andExpect(jsonPath("$[1].foodName").value("탄탄멘"))
				.andExpect(jsonPath("$[1].originName").value("성수동"))
				.andExpect(jsonPath("$[1].createdAt").isString());
	}

	@Test
	void listNeedsLogin() throws Exception {
		mvc.perform(get("/api/saved")).andExpect(status().isUnauthorized());
	}

	@Test
	void onlyTheOwnerCanDeleteAndTheLinkThenStopsWorking() throws Exception {
		String id = save(SEOYUN, MENU_ONLY);

		mvc.perform(withCsrf(delete("/api/saved/" + id), loggedIn(HARAM))).andExpect(status().isNotFound());
		mvc.perform(get("/api/saved/" + id)).andExpect(status().isOk());

		mvc.perform(withCsrf(delete("/api/saved/" + id), loggedIn(SEOYUN))).andExpect(status().isNoContent());
		mvc.perform(get("/api/saved/" + id)).andExpect(status().isNotFound());
	}
}
