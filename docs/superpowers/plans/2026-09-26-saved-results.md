# Saved Results (Sub-project C2b) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Logged-in users can save a result and see their saved list. Each saved result has a public share link `/s/{id}` that can't be guessed. A result holds the menu, plus the places and date courses when the user searched for them, without any Google data. If the user presses save before logging in, the save goes through after the Kakao login round trip.

**Architecture:** A new backend feature, `saved`:
- `SavedController` binds the request body to its own `SavedResult` records. These have no Google components, so Jackson drops Google and unknown fields.
- The controller checks the limits. `SavedRepository` then stores the re-serialized JSON in one `saved_result` row, keyed by a random 22-character id.
- `SecurityConfig` opens only `GET /api/saved/*` to everyone.

On the frontend:
- The place tables and date courses move to `shared/places/`.
- A new `features/saved/` holds the API, save button, share button, saved view, saved list and pending save.
- `App.tsx` switches pages by URL path using `pushState` and `popstate`.

**Tech Stack:** Spring Boot 4.1.1 (`JdbcClient`, Flyway, Jackson 3 `JsonMapper`, Security 7), JUnit 5 + MockMvc; React 19 + TypeScript 6 + Tailwind v4 + `motion` + `@phosphor-icons/react`.

**Spec:** `docs/superpowers/specs/2026-09-26-saved-results-design.md`

## Global Constraints

- Every Gradle command runs with `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` (the default JDK is 11).
- No new dependencies on the backend or the frontend. No JPA, no router library, no validation starter.
- Package by feature:
  - Backend `com.example.pickyourfood.saved` must not import from `place` or `account`. The account id comes from `Authentication.getName()`.
  - Frontend features never import each other. `App.tsx` passes callbacks instead (`AccountMenu.onOpenSaved`, `PlacesSection.onLoaded`).
- Google data is never stored. `rating`, `reviewCount`, `priceLevel`, `reviews` and `googleUrl` never appear in the stored payload or the API response.
- Id: 16 `SecureRandom` bytes, base64url without padding (22 characters).
- Limits (anything outside them gets 400):
  - `title` is 1–100 characters.
  - `best.id` and `best.name` are required; `best.name` is at most 100 characters.
  - At most 5 alternatives, 10 nearby places, 10 famous places, 3 courses, and 2 legs per course.
  - No `null` list items.
  - When places are present, `origin.name` is required and at most 200 characters.
  - The JSON payload is at most 65,536 characters.
- Access:
  - `POST /api/saved`: login + CSRF → `201 {id}`.
  - `GET /api/saved`: login.
  - `GET /api/saved/{id}`: public → `{id, createdAt, mine, result}`, or 404.
  - `DELETE /api/saved/{id}`: login + CSRF → 204 for the owner, 404 for anyone else.
- Copy (exact strings):
  - Save button: `저장하기`, `저장하는 중…`, `저장했어요`, `저장하지 못했어요. 다시 시도해 주세요.`
  - Share: `링크 공유`, `링크를 복사했어요`
  - Saved view: `저장한 결과 · 9월 26일`, `이 결과를 지울까요? 링크도 더 이상 열리지 않아요.`, `이 결과를 찾을 수 없어요.` + `삭제되었거나 주소가 잘못됐어요.`
  - Saved list: `저장한 결과`, `아직 저장한 결과가 없어요.` + `메뉴를 뽑고 저장해 보세요.`, `로그인하면 저장한 결과를 볼 수 있어요.`, `삭제하지 못했어요. 다시 시도해 주세요.`
- Design:
  - Zinc base plus one `accent` color, Phosphor icons, no emojis.
  - Motion uses the spring `{ type: 'spring', stiffness: 100, damping: 20 }`.
  - Lists use `divide-y`, not cards.
  - No horizontal scroll at 375px.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never commit `.claude/`, `.serena/`, `graphify-out/` or `data/`.

## Review Focus

1. **A login fails after a save was stashed (`/?login=failed`).** Nothing is saved and the stash is gone. `AccountMenu`'s effect strips the flag before `App`'s effect runs, so `pending.ts` reads it when the module loads. Pinned by the `pending-failed` browser scenario (Task 2).
2. **Someone else, or a guessed id, tries `DELETE /api/saved/{id}`.** The answer is `404`, the result survives, and the answer does not reveal whether the result exists. Pinned by `SavedControllerTest.onlyTheOwnerCanDeleteAndTheLinkThenStopsWorking` (Task 1).
3. **A crafted body contains Google fields, unknown fields, `null` list items or a huge description.** Google and unknown fields are dropped from the stored JSON; the rest gets `400`, never `500`. Pinned by `savedResultGetsAnUnguessableLinkAndDropsGoogleAndUnknownFields` and `malformedOrOversizedResultsAreRejected` (Task 1).
4. **The user searches a new place after saving.** The button goes back to `저장하기` so the new places can be saved. A new draw resets it too. Pinned by the `save` scenario (Task 2).
5. **Long titles or origins in the list and view at 375px.** They are truncated, with no horizontal scroll. Pinned by the long title in the `list` data and by the `mobile` scenario (Task 2).

---

### Task 1: Backend — saved results API

**Files:**
- Create: `src/main/resources/db/migration/V3__saved_result.sql`
- Create: `src/main/java/com/example/pickyourfood/saved/SavedResult.java`
- Create: `src/main/java/com/example/pickyourfood/saved/SavedRepository.java`
- Create: `src/main/java/com/example/pickyourfood/saved/SavedController.java`
- Modify: `src/main/java/com/example/pickyourfood/account/SecurityConfig.java`
- Test: `src/test/java/com/example/pickyourfood/saved/SavedControllerTest.java`

**Interfaces:**
- Consumes from C2a:
  - the `account` table (`id bigint`),
  - sessions and the principal (`Authentication.getName()` is the account id as a string),
  - `csrf.spa()`.
- Produces (HTTP, consumed by Task 2):
  - `POST /api/saved`
    - Body: `{title, best: Food, alternatives: Food[], places: {origin, nearby: Place[], famous: Place[], dateCourses: DateCourse[]} | null}`
    - Responses: `201 {"id": string}`, `400`, `401`, or `403` without `X-XSRF-TOKEN`.
  - `GET /api/saved` → `200 [{id, title, foodName, originName: string | null, createdAt: ISO string}]` newest first, or `401`.
  - `GET /api/saved/{id}` → `200 {id, createdAt, mine: boolean, result}`, or `404`.
    - `result` has the saved shape. Places are `{id, name, address, distanceMeters, lat, lng, kakaoUrl}` with no Google fields.
    - `places` is `null` for a menu-only save.
  - `DELETE /api/saved/{id}` → `204` or `404`; `401` without a login; `403` without the CSRF header.

- [ ] **Step 1: Write the failing controller test**

Create `src/test/java/com/example/pickyourfood/saved/SavedControllerTest.java`. As in `AccountControllerTest`, logins are real sessions stored in the session table. Accounts 1001 and 1002 are merged in first so the foreign key holds.

```java
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
```

- [ ] **Step 2: Run it to see it fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests '*SavedControllerTest'`
Expected: FAIL.
- `savingNeedsLogin`, `savingNeedsTheCsrfHeader` and `listNeedsLogin` already pass, because every `/api/**` path needs a login and CSRF runs first.
- The other tests fail: `GET /api/saved/…` answers `401`, a logged-in `POST` answers `404`, and there is no `saved_result` table.

- [ ] **Step 3: Add the table**

`src/main/resources/db/migration/V3__saved_result.sql`:

```sql
create table saved_result (
	id varchar(22) primary key,
	account_id bigint not null references account (id),
	title varchar(100) not null,
	food_name varchar(100) not null,
	origin_name varchar(200),
	payload varchar(65536) not null,
	created_at timestamp not null
);

create index saved_result_account on saved_result (account_id, created_at);
```

- [ ] **Step 4: Add the saved shape**

`src/main/java/com/example/pickyourfood/saved/SavedResult.java`:

```java
package com.example.pickyourfood.saved;

import java.util.List;

// what a saved link shows; Google fields (rating, reviews, …) have no component here, so they are dropped on save
record SavedResult(String title, Food best, List<Food> alternatives, Places places) {

	record Food(String id, String name, String description) {
	}

	record Places(Origin origin, List<Place> nearby, List<Place> famous, List<DateCourse> dateCourses) {
	}

	record Origin(String name, double lat, double lng) {
	}

	record Place(String id, String name, String address, int distanceMeters, double lat, double lng, String kakaoUrl) {
	}

	record Spot(String id, String name, String category, String address, double lat, double lng, String kakaoUrl) {
	}

	record Leg(String from, String to, int meters, int walkMinutes) {
	}

	record DateCourse(Place restaurant, Spot cafe, Spot sight, List<Leg> legs, String routeUrl) {
	}
}
```

- [ ] **Step 5: Add the repository**

`src/main/java/com/example/pickyourfood/saved/SavedRepository.java`:

```java
package com.example.pickyourfood.saved;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SavedRepository {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	SavedRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// the id is the share link, so it has to be unguessable: 16 random bytes, 22 url-safe characters
	String create(long accountId, SavedResult result, String payload) {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		String origin = result.places() == null ? null : result.places().origin().name();
		jdbc.sql("insert into saved_result (id, account_id, title, food_name, origin_name, payload, created_at) values (?, ?, ?, ?, ?, ?, ?)")
				.params(id, accountId, result.title(), result.best().name(), origin, payload, Timestamp.from(Instant.now()))
				.update();
		return id;
	}

	List<Summary> findByAccount(long accountId) {
		return jdbc.sql("select id, title, food_name, origin_name, created_at from saved_result where account_id = ? order by created_at desc")
				.param(accountId)
				.query((rs, row) -> new Summary(rs.getString("id"), rs.getString("title"), rs.getString("food_name"),
						rs.getString("origin_name"), rs.getTimestamp("created_at").toInstant()))
				.list();
	}

	Optional<Stored> find(String id) {
		return jdbc.sql("select account_id, payload, created_at from saved_result where id = ?").param(id)
				.query((rs, row) -> new Stored(rs.getLong("account_id"), rs.getString("payload"), rs.getTimestamp("created_at").toInstant()))
				.optional();
	}

	// false when there is no such result or it belongs to someone else
	boolean delete(String id, long accountId) {
		return jdbc.sql("delete from saved_result where id = ? and account_id = ?").params(id, accountId).update() == 1;
	}

	record Summary(String id, String title, String foodName, String originName, Instant createdAt) {
	}

	record Stored(long accountId, String payload, Instant createdAt) {
	}
}
```

- [ ] **Step 6: Add the controller**

`src/main/java/com/example/pickyourfood/saved/SavedController.java`:

```java
package com.example.pickyourfood.saved;

import com.example.pickyourfood.saved.SavedRepository.Stored;
import com.example.pickyourfood.saved.SavedRepository.Summary;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/saved")
class SavedController {

	private static final int MAX_PAYLOAD = 65_536;

	private final SavedRepository saved;
	private final JsonMapper json;

	SavedController(SavedRepository saved, JsonMapper json) {
		this.saved = saved;
		this.json = json;
	}

	// the principal's name is the account id (AccountService builds it that way)
	private static long accountId(Authentication auth) {
		return Long.parseLong(auth.getName());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	Created save(@RequestBody SavedResult result, Authentication auth) {
		if (!valid(result)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		String payload = json.writeValueAsString(result);
		if (payload.length() > MAX_PAYLOAD) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		return new Created(saved.create(accountId(auth), result, payload));
	}

	@GetMapping
	List<Summary> mine(Authentication auth) {
		return saved.findByAccount(accountId(auth));
	}

	// public: anyone with the link sees it; auth is null for visitors
	@GetMapping("/{id}")
	View one(@PathVariable String id, Authentication auth) {
		Stored stored = saved.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		boolean mine = auth != null && auth.getName().equals(String.valueOf(stored.accountId()));
		return new View(id, stored.createdAt(), mine, json.readValue(stored.payload(), SavedResult.class));
	}

	// someone else's result answers 404 too, so ids of others' results can't be probed
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable String id, Authentication auth) {
		if (!saved.delete(id, accountId(auth))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
	}

	private static boolean valid(SavedResult r) {
		return r.title() != null && !r.title().isBlank() && r.title().length() <= 100
				&& r.best() != null && r.best().id() != null && r.best().name() != null && r.best().name().length() <= 100
				&& fits(r.alternatives(), 5)
				&& (r.places() == null || valid(r.places()));
	}

	private static boolean valid(SavedResult.Places p) {
		return p.origin() != null && p.origin().name() != null && p.origin().name().length() <= 200
				&& fits(p.nearby(), 10) && fits(p.famous(), 10) && fits(p.dateCourses(), 3)
				&& p.dateCourses().stream().allMatch(c -> c.restaurant() != null && fits(c.legs(), 2));
	}

	private static boolean fits(List<?> list, int max) {
		return list != null && list.size() <= max && !list.contains(null);
	}

	record Created(String id) {
	}

	record View(String id, Instant createdAt, boolean mine, SavedResult result) {
	}
}
```

- [ ] **Step 7: Open the share link to everyone**

`src/main/java/com/example/pickyourfood/account/SecurityConfig.java`, whole file after the change:

```java
package com.example.pickyourfood.account;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

@Configuration
class SecurityConfig {

	@Bean
	SecurityFilterChain security(HttpSecurity http, AccountService accounts) throws Exception {
		http
				.authorizeHttpRequests(requests -> requests
						.requestMatchers("/api/foods/**", "/api/recommendations", "/api/places").permitAll()
						// a saved result's link is public; saving, listing and deleting need a login
						.requestMatchers(HttpMethod.GET, "/api/saved/*").permitAll()
						.requestMatchers("/api/**").authenticated()
						.anyRequest().permitAll())
				// an API call without a login gets 401 instead of a redirect to a login page
				.exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				.oauth2Login(login -> login
						.userInfoEndpoint(userInfo -> userInfo.userService(accounts))
						.defaultSuccessUrl("/", true)
						.failureUrl("/?login=failed"))
				.logout(logout -> logout
						.logoutUrl("/api/logout")
						.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
				// the recommendation POST reads no session or account, so it needs no token
				.csrf(csrf -> csrf.spa().ignoringRequestMatchers("/api/recommendations"));
		return http.build();
	}
}
```

- [ ] **Step 8: Run the controller test**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests '*SavedControllerTest'`
Expected: PASS, 10 tests.

- [ ] **Step 9: Run the whole suite**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test`
Expected: BUILD SUCCESSFUL, 71 tests (61 existing + 10 new), 0 failures. `AccountControllerTest.otherApiPathsNeedLoginAndAnswer401InsteadOfRedirecting` (`GET /api/saved` → 401) still passes.

- [ ] **Step 10: Commit**

```bash
git add src/main/resources/db/migration/V3__saved_result.sql src/main/java/com/example/pickyourfood/saved src/main/java/com/example/pickyourfood/account/SecurityConfig.java src/test/java/com/example/pickyourfood/saved
git commit -m "feat: add saved results API with public share links

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 2: Frontend — save button, saved view and list, pending save

**Files:**
- Move: `frontend/src/features/places/{PlaceTable.tsx, DateCourses.tsx, MapLink.tsx, format.ts}` → `frontend/src/shared/places/`
- Create: `frontend/src/shared/places/types.ts` (the place types, moved out of `features/places/api.ts`)
- Modify: `frontend/src/features/places/api.ts`, `frontend/src/features/places/PlacesSection.tsx`
- Create: `frontend/src/features/saved/{api.ts, pending.ts, ShareButton.tsx, SaveButton.tsx, SavedView.tsx, SavedList.tsx}`
- Modify: `frontend/src/features/account/AccountMenu.tsx`, `frontend/src/App.tsx`, `CLAUDE.md`
- Test: `<workspace>/saved-check.mjs` (headless Chrome; not committed)

**Interfaces:**
- Consumes:
  - From Task 1: the four `/api/saved` endpoints above.
  - From C2a: `request()`, which adds the CSRF header on non-GET requests and returns `undefined` for 204, and `HttpError`.
- Produces:
  - `shared/places/types.ts`: `Review`, `Place`, `Spot`, `Leg`, `DateCourse`, `Places` (shapes unchanged).
  - `features/saved/api.ts`:
    - `type Saved = { title; best: Food; alternatives: Food[]; places: Places | null }`, `SavedEntry`, `SavedSummary`
    - `saveResult(saved): Promise<string>`
    - `fetchSaved(id): Promise<SavedEntry>`
    - `fetchMySaved(): Promise<SavedSummary[] | null>` (null on 401)
    - `deleteSaved(id): Promise<void>`
    - `savedDate(iso): string`
  - `features/saved/pending.ts`: `stashPending(saved)`, `resumePending(): Promise<string> | null`.
  - New props: `PlacesSection.onLoaded(places: Places | null)` and `AccountMenu.onOpenSaved()`.

- [ ] **Step 1: Write the browser check script**

`<workspace>/saved-check.mjs` answers every `/api/*` call itself and stands in for the Kakao round trip: a `302` back to `/` that switches the mock to logged in. It stubs out `navigator.share` (deleted), the clipboard and `confirm`. Requires Chrome at the default macOS path and Node 22+ (built-in `WebSocket`).

```js
// usage: node saved-check.mjs <save|pending|pending-failed|view|missing|mine|list|empty|mobile>   (Vite dev server on :5173)
import { spawn } from 'node:child_process'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scenario = process.argv[2] ?? 'save'
const BASE = process.env.BASE ?? 'http://localhost:5173'
const PORT = 9334
const chrome = spawn(
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${mkdtempSync(join(tmpdir(), 'saved-'))}`, '--window-size=1280,900', 'about:blank'],
  { stdio: 'ignore' },
)
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

let target
for (let i = 0; i < 50 && !target; i++) {
  await sleep(200)
  try {
    target = (await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()).find((t) => t.type === 'page')
  } catch {}
}
const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve) => ws.addEventListener('open', resolve, { once: true }))
let nextId = 0
const pending = new Map()
const listeners = []
ws.addEventListener('message', ({ data }) => {
  const msg = JSON.parse(data)
  if (msg.id !== undefined) {
    pending.get(msg.id)?.(msg)
    pending.delete(msg.id)
  } else listeners.forEach((listener) => listener(msg))
})
const send = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = ++nextId
    pending.set(id, (msg) => (msg.error ? reject(new Error(`${method}: ${msg.error.message}`)) : resolve(msg.result)))
    ws.send(JSON.stringify({ id, method, params }))
  })
const js = async (expression) => (await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })).result.value
const text = () => js('document.body.innerText')
async function waitFor(snippet, ms = 5000) {
  for (let t = 0; t < ms; t += 100) {
    if ((await text()).includes(snippet)) return
    await sleep(100)
  }
  throw new Error(`timed out waiting for "${snippet}"\n---\n${await text()}`)
}
const click = (label) =>
  js(`(() => { const el = [...document.querySelectorAll('button')].find((b) => b.innerText.trim().includes(${JSON.stringify(label)})); if (!el) throw new Error('no button: ' + ${JSON.stringify(label)}); el.click() })()`)
const loginHref = () => js(`[...document.querySelectorAll('a')].find((a) => a.innerText.includes('카카오로 로그인'))?.getAttribute('href') ?? null`)
const check = (label, ok) => {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}`)
  if (!ok) process.exitCode = 1
}
const screenshot = async (name) =>
  writeFileSync(join(import.meta.dirname, `${name}.png`), Buffer.from((await send('Page.captureScreenshot', { captureBeyondViewport: true })).data, 'base64'))


const FOOD = { id: 'tantanmen', name: '탄탄멘', description: '고소하고 매콤한 국물' }
const place = (id, name, meters) => ({
  id, name, address: `서울 성동구 연무장길 ${id}`, distanceMeters: meters, lat: 37.544, lng: 127.056, kakaoUrl: `http://place.map.kakao.com/${id}`,
  rating: 4.4, reviewCount: 1284, priceLevel: 2, reviews: [{ author: '민지', rating: 5, text: '국물이 진해요', when: '1주 전' }], googleUrl: 'https://maps.google.com/?cid=1',
})
const cafe = { id: 'c1', name: '어니언 성수', category: '카페', address: '서울 성동구 1', lat: 37.541, lng: 127.051, kakaoUrl: 'http://place.map.kakao.com/c1' }
const PLACES = {
  origin: { name: '성수동', lat: 37.54, lng: 127.05 },
  nearby: [place('1', '탄탄면공방', 320), place('2', '담담', 540)],
  famous: [place('3', '옛날탄탄', 4200)],
  dateCourses: [
    { restaurant: place('1', '탄탄면공방', 320), cafe, sight: null, legs: [{ from: '탄탄면공방', to: '어니언 성수', meters: 320, walkMinutes: 5 }], routeUrl: 'https://map.kakao.com/link/by/walk/a' },
    { restaurant: place('2', '담담', 540), cafe: null, sight: null, legs: [], routeUrl: null },
  ],
}
const strip = ({ id, name, address, distanceMeters, lat, lng, kakaoUrl }) => ({ id, name, address, distanceMeters, lat, lng, kakaoUrl })
const SAVED_ID = 'Xk3v9QpL2mZt7RbN4cHs1w'
const ENTRY = (mine) => ({
  id: SAVED_ID, createdAt: '2026-09-26T09:12:00Z', mine,
  result: { title: '오늘의 랜덤 메뉴', best: FOOD, alternatives: [{ id: 'ramen', name: '라멘', description: '진한 국물' }], places: {
    ...PLACES, nearby: PLACES.nearby.map(strip), famous: PLACES.famous.map(strip),
    dateCourses: PLACES.dateCourses.map((c) => ({ ...c, restaurant: strip(c.restaurant) })) } },
})
let list = scenario === 'empty' ? [] : [
  { id: SAVED_ID, title: '오늘의 랜덤 메뉴', foodName: '탄탄멘', originName: '성수동', createdAt: '2026-09-26T09:12:00Z' },
  { id: 'Ab12Cd34Ef56Gh78Ij90Kl', title: '당신에게 딱 맞는 메뉴서윤하람도윤의 아주 아주 긴 제목이 들어가도 한 줄로', foodName: '비빔밥', originName: null, createdAt: '2026-09-20T03:00:00Z' },
]

let loggedIn = !['pending', 'pending-failed', 'view', 'missing'].includes(scenario)
const posts = []
const deletes = []
function answer(request) {
  const url = new URL(request.url)
  if (url.pathname === '/api/me') return loggedIn ? [200, { id: 7, nickname: '서윤' }] : [401, {}]
  if (url.pathname === '/api/foods/random') return [200, FOOD]
  if (url.pathname === '/api/places') return [200, PLACES]
  if (url.pathname === '/api/saved' && request.method === 'POST') {
    posts.push({ body: JSON.parse(request.postData), headers: request.headers, loggedIn })
    return loggedIn ? [201, { id: SAVED_ID }] : [401, {}]
  }
  if (url.pathname === '/api/saved') return loggedIn ? [200, list] : [401, {}]
  if (url.pathname.startsWith('/api/saved/')) {
    const id = url.pathname.split('/').pop()
    if (request.method === 'DELETE') {
      deletes.push({ id, headers: request.headers })
      list = list.filter((item) => item.id !== id)
      return [204, null]
    }
    return id === SAVED_ID && scenario !== 'missing' ? [200, ENTRY(scenario === 'mine')] : [404, {}]
  }
  return [404, {}]
}
listeners.push(async (msg) => {
  if (msg.method !== 'Fetch.requestPaused') return
  const { request, requestId } = msg.params
  if (request.url.includes('/oauth2/authorization/kakao')) {
    // stands in for the Kakao round trip: logged in, back on /
    loggedIn = true
    await send('Fetch.fulfillRequest', { requestId, responseCode: 302, responseHeaders: [{ name: 'Location', value: `${BASE}/` }], body: '' })
    return
  }
  const [status, body] = answer(request)
  await send('Fetch.fulfillRequest', {
    requestId,
    responseCode: status,
    responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
    body: body === null ? '' : Buffer.from(JSON.stringify(body)).toString('base64'),
  })
})

const typeInto = (selector, value) =>
  js(`(() => { const el = document.querySelector(${JSON.stringify(selector)}); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, ${JSON.stringify(value)}); el.dispatchEvent(new Event('input', { bubbles: true })) })()`)
const noScroll = async () => (await js('document.documentElement.scrollWidth')) <= (await js('window.innerWidth'))

async function drawAndSearch() {
  await waitFor('랜덤으로 뽑기')
  await click('랜덤으로 뽑기')
  await waitFor('고소하고 매콤한 국물')
  await typeInto('#near', '성수동')
  await click('찾기')
  await waitFor('성수동 기준')
}

await send('Page.enable')
await send('Runtime.enable')
await send('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }, { urlPattern: '*/oauth2/*' }] })
await send('Page.addScriptToEvaluateOnNewDocument', {
  source: `document.cookie = 'XSRF-TOKEN=token-4f2a; path=/'
    delete Navigator.prototype.share
    window.__copied = null
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: (text) => { window.__copied = text; return Promise.resolve() } } })
    window.confirm = (message) => { window.__confirmed = message; return true }
    ${scenario === 'pending-failed' ? `if (!window.name) { sessionStorage.setItem('pending-save', JSON.stringify({ title: 't', best: ${JSON.stringify(FOOD)}, alternatives: [], places: null })); window.name = 'seeded' }` : ''}`,
})
if (scenario === 'mobile') await send('Emulation.setDeviceMetricsOverride', { width: 375, height: 812, deviceScaleFactor: 2, mobile: true })

const start = { view: `/s/${SAVED_ID}`, missing: '/s/nope', mine: `/s/${SAVED_ID}`, list: '/saved', empty: '/saved', 'pending-failed': '/?login=failed' }

try {
  await send('Page.navigate', { url: `${BASE}${start[scenario] ?? '/'}` })

  if (scenario === 'save') {
    await drawAndSearch()
    await click('저장하기')
    await waitFor('저장했어요')
    const { body, headers } = posts[0]
    check('sends the CSRF header', headers['x-xsrf-token'] === 'token-4f2a')
    check('sends menu and places', body.best.name === '탄탄멘' && body.places.origin.name === '성수동' && body.places.dateCourses.length === 2)
    check('sends no Google fields', !JSON.stringify(body).match(/rating|reviews|priceLevel|googleUrl|reviewCount/))
    await click('링크 공유')
    await waitFor('링크를 복사했어요')
    check('copies the share link', (await js('window.__copied')) === `${BASE}/s/${SAVED_ID}`)
    await sleep(1500)
    await screenshot('saved-save')
    await typeInto('#near', '강남역')
    await click('찾기')
    await waitFor('저장하기')
    check('a new search can be saved again', !(await text()).includes('저장했어요'))
  } else if (scenario === 'pending') {
    await drawAndSearch()
    await click('저장하기')
    await waitFor('저장한 결과 ·', 8000)
    await waitFor('저장했어요')
    check('first save was refused, then saved after login', posts.length === 2 && !posts[0].loggedIn && posts[1].loggedIn)
    check('the resumed save carries the places', posts[1].body.places?.origin.name === '성수동')
    check('lands on the saved link', (await js('location.pathname')) === `/s/${SAVED_ID}`)
    check('stash cleared', (await js(`sessionStorage.getItem('pending-save')`)) === null)
  } else if (scenario === 'pending-failed') {
    await waitFor('로그인하지 못했어요')
    await sleep(500)
    check('no save after a failed login', posts.length === 0)
    check('stash cleared', (await js(`sessionStorage.getItem('pending-save')`)) === null)
  } else if (scenario === 'view') {
    await waitFor('저장한 결과 · 9월 26일')
    const page = await text()
    check('menu and alternative', page.includes('탄탄멘') && page.includes('라멘'))
    check('places from the saved origin', page.includes('성수동 기준') && page.includes('탄탄면공방'))
    check('course tabs', page.includes('01') && page.includes('담담'))
    check('no delete for visitors', !page.includes('삭제'))
    check('no "just saved" notice on a plain visit', !page.includes('저장했어요'))
    await sleep(1500)
    await screenshot('saved-view')
    await click('처음으로')
    await waitFor('랜덤으로 뽑기')
    check('home goes back to /', (await js('location.pathname')) === '/')
  } else if (scenario === 'missing') {
    await waitFor('이 결과를 찾을 수 없어요.')
    check('explains why', (await text()).includes('삭제되었거나 주소가 잘못됐어요.'))
  } else if (scenario === 'mine') {
    await waitFor('저장한 결과 · 9월 26일')
    await click('삭제')
    await waitFor('저장한 결과', 5000)
    await waitFor('비빔밥')
    check('asked before deleting', (await js('window.__confirmed')) === '이 결과를 지울까요? 링크도 더 이상 열리지 않아요.')
    check('delete sends the CSRF header', deletes[0]?.id === SAVED_ID && deletes[0].headers['x-xsrf-token'] === 'token-4f2a')
    check('back on the list without it', (await js('location.pathname')) === '/saved' && !(await text()).includes('탄탄멘'))
  } else if (scenario === 'list') {
    await waitFor('탄탄멘')
    check('rows with origin', (await text()).includes('오늘의 랜덤 메뉴 · 성수동 기준'))
    await sleep(1500)
    await screenshot('saved-list')
    await js(`[...document.querySelectorAll('li button')].find((b) => b.innerText.includes('탄탄멘')).click()`)
    await waitFor('저장한 결과 · 9월 26일')
    check('row opens the link', (await js('location.pathname')) === `/s/${SAVED_ID}`)
    await js('history.back()')
    await waitFor('비빔밥')
    check('back returns to the list', (await js('location.pathname')) === '/saved')
    await js(`document.querySelector('[aria-label="비빔밥 삭제"]').click()`)
    await sleep(800)
    check('row delete removes it', !(await text()).includes('비빔밥') && deletes[0]?.id === 'Ab12Cd34Ef56Gh78Ij90Kl')
    // the account menu reaches the list from anywhere
    await click('처음으로')
    await waitFor('랜덤으로 뽑기')
    await click('서윤님')
    await click('저장한 결과')
    await waitFor('탄탄멘')
    check('account menu opens the list', (await js('location.pathname')) === '/saved')
  } else if (scenario === 'empty') {
    await waitFor('아직 저장한 결과가 없어요.')
    check('empty state hint', (await text()).includes('메뉴를 뽑고 저장해 보세요.'))
  } else if (scenario === 'mobile') {
    await drawAndSearch()
    await click('저장하기')
    await waitFor('저장했어요')
    await sleep(1500)
    check('result: no horizontal scroll at 375px', await noScroll())
    await screenshot('saved-mobile-result')
    await click('서윤님')
    await click('저장한 결과')
    await waitFor('비빔밥')
    await sleep(1500)
    check('list: no horizontal scroll at 375px', await noScroll())
    await screenshot('saved-mobile-list')
    await js(`[...document.querySelectorAll('li button')].find((b) => b.innerText.includes('탄탄멘')).click()`)
    await waitFor('성수동 기준')
    await sleep(1500)
    check('view: no horizontal scroll at 375px', await noScroll())
    await screenshot('saved-mobile-view')
  }
} catch (error) {
  console.log(`FAIL ${error.message}`)
  process.exitCode = 1
} finally {
  ws.close()
  chrome.kill()
}
```

- [ ] **Step 2: Run it to see it fail**

Start Vite in the background: `cd frontend && npx vite --port 5173 --strictPort`.
Run: `node <workspace>/saved-check.mjs save`
Expected: `FAIL` while waiting for `저장하기`, because there is no save button yet.

- [ ] **Step 3: Move the place display to `shared/places/`**

```bash
cd frontend
mkdir -p src/shared/places
git mv src/features/places/PlaceTable.tsx src/features/places/DateCourses.tsx src/features/places/MapLink.tsx src/features/places/format.ts src/shared/places/
```

Create `frontend/src/shared/places/types.ts` with the types moved out of `features/places/api.ts`, unchanged:

```ts
export type Review = {
  author: string | null
  rating: number | null
  text: string
  when: string | null
}

// rating, reviewCount, priceLevel and googleUrl are null when Google has no match
export type Place = {
  id: string
  name: string
  address: string
  distanceMeters: number
  lat: number
  lng: number
  kakaoUrl: string
  rating: number | null
  reviewCount: number | null
  priceLevel: number | null
  reviews: Review[]
  googleUrl: string | null
}

export type Spot = {
  id: string
  name: string
  category: string
  address: string
  lat: number
  lng: number
  kakaoUrl: string
}

// straight-line distance between two stops
export type Leg = {
  from: string
  to: string
  meters: number
  walkMinutes: number
}

// legs join only the stops that exist; routeUrl is null when the restaurant is the only stop
export type DateCourse = {
  restaurant: Place
  cafe: Spot | null
  sight: Spot | null
  legs: Leg[]
  routeUrl: string | null
}

export type Places = {
  origin: { name: string; lat: number; lng: number }
  nearby: Place[]
  famous: Place[]
  dateCourses: DateCourse[]
}
```

`frontend/src/features/places/api.ts` becomes:

```ts
import { request } from '../../shared/api.ts'
import type { Places } from '../../shared/places/types.ts'

export type Where = { lat: number; lng: number } | { near: string }

export function fetchPlaces(food: string, where: Where): Promise<Places> {
  const params = new URLSearchParams(
    'near' in where ? { food, near: where.near } : { food, lat: String(where.lat), lng: String(where.lng) },
  )
  return request(`/api/places?${params}`)
}
```

In `shared/places/PlaceTable.tsx`, `DateCourses.tsx` and `format.ts`, change `from './api.ts'` to `from './types.ts'`. Nothing else changes in the moved files.

- [ ] **Step 4: Let `PlacesSection` report the places on screen**

`frontend/src/features/places/PlacesSection.tsx`, whole file after the change:

```tsx
import { Crosshair, MapPin } from '@phosphor-icons/react'
import { useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { HttpError } from '../../shared/api.ts'
import DateCourses from '../../shared/places/DateCourses.tsx'
import PlaceTable from '../../shared/places/PlaceTable.tsx'
import type { Places } from '../../shared/places/types.ts'
import { fetchPlaces } from './api.ts'
import type { Where } from './api.ts'

type State = { status: 'idle' | 'locating' | 'loading' | 'error' } | { status: 'done'; data: Places }

type Props = {
  food: string
  // the places on screen: null while searching or before the first search
  onLoaded: (places: Places | null) => void
}

export default function PlacesSection({ food, onLoaded }: Props) {
  const [state, setState] = useState<State>({ status: 'idle' })
  const [notice, setNotice] = useState('')
  const [query, setQuery] = useState('')
  const input = useRef<HTMLInputElement>(null)
  // a slower, older search (or location request) must not overwrite a newer one
  const latest = useRef(0)
  const lastWhere = useRef<Where | null>(null)

  function search(where: Where) {
    const id = ++latest.current
    lastWhere.current = where
    setNotice('')
    setState({ status: 'loading' })
    onLoaded(null)
    fetchPlaces(food, where).then(
      (data) => {
        if (id !== latest.current) return
        setState({ status: 'done', data })
        onLoaded(data)
      },
      (error: unknown) => {
        if (id !== latest.current) return
        if (error instanceof HttpError && error.status === 404 && 'near' in where) {
          setNotice(`'${where.near}'을 찾지 못했어요.`)
          setState({ status: 'idle' })
        } else {
          setState({ status: 'error' })
        }
      },
    )
  }

  function denied() {
    setState({ status: 'idle' })
    setNotice('위치 권한이 없어요. 동네 이름으로 찾아보세요.')
    input.current?.focus()
  }

  function locate() {
    const id = ++latest.current
    setNotice('')
    if (!navigator.geolocation) {
      denied()
      return
    }
    setState({ status: 'locating' })
    navigator.geolocation.getCurrentPosition(
      (position) => id === latest.current && search({ lat: position.coords.latitude, lng: position.coords.longitude }),
      () => id === latest.current && denied(),
      { timeout: 10_000 },
    )
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const near = query.trim()
    if (near) search({ near })
  }

  return (
    <section className="mt-20 border-t border-zinc-200 pt-12">
      <div className="grid gap-8 md:grid-cols-[1fr_1.2fr] md:items-end">
        <h2 className="text-3xl font-semibold tracking-tight md:text-4xl">이 메뉴, 어디서 먹지?</h2>
        <form onSubmit={submit} className="grid gap-2">
          <label htmlFor="near" className="text-sm text-zinc-500">
            동네나 역 이름
          </label>
          <div className="flex flex-wrap gap-2">
            <input
              id="near"
              ref={input}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="성수동, 강남역"
              className="min-w-0 flex-1 basis-40 rounded-full border border-zinc-300 bg-white px-5 py-3 text-sm outline-none transition focus:border-accent"
            />
            <button
              type="submit"
              disabled={!query.trim()}
              className="rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98] disabled:opacity-40"
            >
              찾기
            </button>
            <button
              type="button"
              onClick={locate}
              disabled={state.status === 'locating'}
              className="flex items-center gap-2 rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98] disabled:opacity-40"
            >
              <Crosshair size={16} /> {state.status === 'locating' ? '위치 확인 중' : '현재 위치로'}
            </button>
          </div>
          {notice && (
            <p role="alert" className="text-sm text-accent">
              {notice}
            </p>
          )}
        </form>
      </div>

      <div className="mt-12">
        {(state.status === 'idle' || state.status === 'locating') && (
          <p className="text-base text-zinc-500">위치를 정하면 주변 맛집과 데이트 코스를 보여드릴게요.</p>
        )}
        {state.status === 'loading' && <PlacesSkeleton />}
        {state.status === 'error' && (
          <div className="rounded-3xl border border-zinc-200 bg-white p-8">
            <p className="text-lg font-semibold">주변 맛집을 불러오지 못했어요.</p>
            <p className="mt-2 text-sm text-zinc-500">잠시 후 다시 시도해 주세요.</p>
            <button
              type="button"
              onClick={() => lastWhere.current && search(lastWhere.current)}
              className="mt-6 rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98]"
            >
              다시 시도
            </button>
          </div>
        )}
        {state.status === 'done' && (
          <>
            <p className="flex items-center gap-2 text-sm text-zinc-500">
              <MapPin size={16} className="text-accent" /> {state.data.origin.name} 기준
            </p>
            <div className="mt-6">
              <PlaceTable food={food} nearby={state.data.nearby} famous={state.data.famous} />
            </div>
            {state.data.dateCourses.length > 0 && (
              <div className="mt-16">
                <DateCourses courses={state.data.dateCourses} />
              </div>
            )}
          </>
        )}
      </div>
    </section>
  )
}

function PlacesSkeleton() {
  return (
    <div className="animate-pulse">
      <div className="h-10 w-56 rounded-full bg-zinc-200" />
      <div className="mt-6 divide-y divide-zinc-200">
        {[0, 1, 2, 3, 4].map((row) => (
          <div key={row} className="flex items-center justify-between py-5">
            <div className="h-5 w-40 rounded-full bg-zinc-200" />
            <div className="h-4 w-24 rounded-full bg-zinc-200" />
          </div>
        ))}
      </div>
      <div className="mt-16 h-6 w-48 rounded-full bg-zinc-200" />
      <div className="mt-6 h-24 rounded-2xl bg-zinc-200" />
    </div>
  )
}
```

- [ ] **Step 5: Add the saved API**

`frontend/src/features/saved/api.ts`:

```ts
import { HttpError, request } from '../../shared/api.ts'
import type { Food } from '../../shared/api.ts'
import type { DateCourse, Place, Places } from '../../shared/places/types.ts'

// what gets saved: the menu and, when searched, the places and date courses around it
export type Saved = {
  title: string
  best: Food
  alternatives: Food[]
  places: Places | null
}

export type SavedEntry = {
  id: string
  createdAt: string
  mine: boolean
  result: Saved
}

export type SavedSummary = {
  id: string
  title: string
  foodName: string
  originName: string | null
  createdAt: string
}

type KakaoPlace = Pick<Place, 'id' | 'name' | 'address' | 'distanceMeters' | 'lat' | 'lng' | 'kakaoUrl'>

type PlacesOf<P> = Omit<Places, 'nearby' | 'famous' | 'dateCourses'> & {
  nearby: P[]
  famous: P[]
  dateCourses: (Omit<DateCourse, 'restaurant'> & { restaurant: P })[]
}

function mapPlaces<A, B>(places: PlacesOf<A>, map: (place: A) => B): PlacesOf<B> {
  return {
    ...places,
    nearby: places.nearby.map(map),
    famous: places.famous.map(map),
    dateCourses: places.dateCourses.map((course) => ({ ...course, restaurant: map(course.restaurant) })),
  }
}

// Google's terms don't allow keeping its data, so only the Kakao part of a place is sent
function kakaoOnly({ id, name, address, distanceMeters, lat, lng, kakaoUrl }: Place): KakaoPlace {
  return { id, name, address, distanceMeters, lat, lng, kakaoUrl }
}

// the tables show the missing Google fields as unknown
function withoutGoogle(place: KakaoPlace): Place {
  return { ...place, rating: null, reviewCount: null, priceLevel: null, reviews: [], googleUrl: null }
}

export async function saveResult(saved: Saved): Promise<string> {
  const { id } = await request<{ id: string }>('/api/saved', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...saved, places: saved.places && mapPlaces(saved.places, kakaoOnly) }),
  })
  return id
}

export async function fetchSaved(id: string): Promise<SavedEntry> {
  const entry = await request<Omit<SavedEntry, 'result'> & { result: Omit<Saved, 'places'> & { places: PlacesOf<KakaoPlace> | null } }>(
    `/api/saved/${encodeURIComponent(id)}`,
  )
  const places = entry.result.places && mapPlaces(entry.result.places, withoutGoogle)
  return { ...entry, result: { ...entry.result, places } }
}

// null when nobody is logged in
export async function fetchMySaved(): Promise<SavedSummary[] | null> {
  try {
    return await request<SavedSummary[]>('/api/saved')
  } catch (error) {
    if (error instanceof HttpError && error.status === 401) return null
    throw error
  }
}

export function deleteSaved(id: string): Promise<void> {
  return request(`/api/saved/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

// "9월 26일"
export function savedDate(createdAt: string): string {
  return new Date(createdAt).toLocaleDateString('ko-KR', { month: 'long', day: 'numeric' })
}
```

- [ ] **Step 6: Add the pending save**

`frontend/src/features/saved/pending.ts`:

```ts
import { saveResult } from './api.ts'
import type { Saved } from './api.ts'

// survives the page leaving for the Kakao login and coming back
const KEY = 'pending-save'

// read before any effect runs: AccountMenu removes the flag from the address once it has shown its notice
const loginFailed = new URLSearchParams(location.search).get('login') === 'failed'

export function stashPending(saved: Saved) {
  sessionStorage.setItem(KEY, JSON.stringify(saved))
}

// saves what was stashed before the login; null when nothing was stashed or the login failed
export function resumePending(): Promise<string> | null {
  const stashed = sessionStorage.getItem(KEY)
  sessionStorage.removeItem(KEY)
  if (!stashed || loginFailed) return null
  return saveResult(JSON.parse(stashed) as Saved)
}
```

- [ ] **Step 7: Add the share and save buttons**

`frontend/src/features/saved/ShareButton.tsx`:

```tsx
import { LinkSimple } from '@phosphor-icons/react'
import { useState } from 'react'

// the share sheet where there is one (phones), otherwise the link goes to the clipboard
export default function ShareButton({ id }: { id: string }) {
  const [copy, setCopy] = useState<'idle' | 'copied' | 'failed'>('idle')
  const url = `${location.origin}/s/${id}`

  function share() {
    if (navigator.share) {
      // closing the share sheet rejects; nothing to report
      navigator.share({ url }).catch(() => {})
      return
    }
    navigator.clipboard.writeText(url).then(
      () => setCopy('copied'),
      () => setCopy('failed'),
    )
  }

  return (
    <>
      <button
        type="button"
        onClick={share}
        className="flex items-center gap-2 rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]"
      >
        <LinkSimple size={16} /> 링크 공유
      </button>
      {copy === 'copied' && (
        <p role="status" className="text-sm text-zinc-500">
          링크를 복사했어요
        </p>
      )}
      {copy === 'failed' && <p className="basis-full break-all font-mono text-xs text-zinc-500">{url}</p>}
    </>
  )
}
```

`frontend/src/features/saved/SaveButton.tsx`:

```tsx
import { BookmarkSimple, CheckCircle } from '@phosphor-icons/react'
import { useState } from 'react'
import { HttpError } from '../../shared/api.ts'
import { saveResult } from './api.ts'
import type { Saved } from './api.ts'
import { stashPending } from './pending.ts'
import ShareButton from './ShareButton.tsx'

type State = { status: 'idle' | 'saving' | 'failed' } | { status: 'saved'; id: string }

// App remounts this (key) whenever the places change, so a new search can be saved again
export default function SaveButton({ saved }: { saved: Saved }) {
  const [state, setState] = useState<State>({ status: 'idle' })

  function save() {
    setState({ status: 'saving' })
    saveResult(saved).then(
      (id) => setState({ status: 'saved', id }),
      (error: unknown) => {
        if (error instanceof HttpError && error.status === 401) {
          // saved after the login comes back, see resumePending
          stashPending(saved)
          location.assign('/oauth2/authorization/kakao')
        } else {
          setState({ status: 'failed' })
        }
      },
    )
  }

  return (
    <div className="mt-10 flex flex-wrap items-center gap-3">
      {state.status === 'saved' ? (
        <>
          <p className="flex items-center gap-2 text-sm font-medium">
            <CheckCircle size={18} weight="fill" className="text-accent" /> 저장했어요
          </p>
          <ShareButton id={state.id} />
        </>
      ) : (
        <button
          type="button"
          onClick={save}
          disabled={state.status === 'saving'}
          className="flex items-center gap-2 rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98] disabled:opacity-40"
        >
          <BookmarkSimple size={16} /> {state.status === 'saving' ? '저장하는 중…' : '저장하기'}
        </button>
      )}
      {state.status === 'failed' && (
        <p role="alert" className="text-sm text-accent">
          저장하지 못했어요. 다시 시도해 주세요.
        </p>
      )}
    </div>
  )
}
```

- [ ] **Step 8: Add the saved view**

`frontend/src/features/saved/SavedView.tsx`:

```tsx
import { CheckCircle, House, MapPin, Trash } from '@phosphor-icons/react'
import { motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { HttpError } from '../../shared/api.ts'
import DateCourses from '../../shared/places/DateCourses.tsx'
import PlaceTable from '../../shared/places/PlaceTable.tsx'
import { deleteSaved, fetchSaved, savedDate } from './api.ts'
import type { SavedEntry } from './api.ts'
import ShareButton from './ShareButton.tsx'

type State = { status: 'loading' | 'missing' | 'error' } | { status: 'done'; entry: SavedEntry }

type Props = {
  id: string
  justSaved: boolean
  onHome: () => void
  onDeleted: () => void
}

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

const button =
  'flex items-center gap-2 rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98] disabled:opacity-40'

export default function SavedView({ id, justSaved, onHome, onDeleted }: Props) {
  const [state, setState] = useState<State>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [deleting, setDeleting] = useState<'idle' | 'busy' | 'failed'>('idle')

  useEffect(() => {
    let current = true
    fetchSaved(id).then(
      (entry) => current && setState({ status: 'done', entry }),
      (error: unknown) =>
        current && setState({ status: error instanceof HttpError && error.status === 404 ? 'missing' : 'error' }),
    )
    return () => {
      current = false
    }
  }, [id, attempt])

  function remove() {
    if (!confirm('이 결과를 지울까요? 링크도 더 이상 열리지 않아요.')) return
    setDeleting('busy')
    deleteSaved(id).then(onDeleted, () => setDeleting('failed'))
  }

  if (state.status === 'loading') return <ViewSkeleton />

  if (state.status !== 'done') {
    return (
      <div className="max-w-xl">
        <p className="text-2xl font-semibold tracking-tight">
          {state.status === 'missing' ? '이 결과를 찾을 수 없어요.' : '저장한 결과를 불러오지 못했어요.'}
        </p>
        <p className="mt-2 text-base text-zinc-500">
          {state.status === 'missing' ? '삭제되었거나 주소가 잘못됐어요.' : '잠시 후 다시 시도해 주세요.'}
        </p>
        <div className="mt-8 flex flex-wrap gap-3">
          {state.status === 'error' && (
            <button
              type="button"
              onClick={() => {
                setState({ status: 'loading' })
                setAttempt((n) => n + 1)
              }}
              className="rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98]"
            >
              다시 시도
            </button>
          )}
          <button type="button" onClick={onHome} className={button}>
            <House size={16} /> 처음으로
          </button>
        </div>
      </div>
    )
  }

  const { entry } = state
  const { best, alternatives, places } = entry.result

  return (
    <div className="w-full">
      <div className="grid w-full gap-10 md:grid-cols-[1.4fr_1fr] md:items-start">
        <motion.div initial={{ opacity: 0, y: 24 }} animate={{ opacity: 1, y: 0 }} transition={spring}>
          <p className="text-sm font-medium text-accent">
            저장한 결과 · <span className="font-mono">{savedDate(entry.createdAt)}</span>
          </p>
          <p className="mt-2 text-sm text-zinc-500">{entry.result.title}</p>
          <h1 className="mt-4 text-5xl font-semibold leading-none tracking-tighter md:text-7xl">{best.name}</h1>
          <p className="mt-6 max-w-[45ch] text-base leading-relaxed text-zinc-600">{best.description}</p>
          {justSaved && (
            <p role="status" className="mt-6 flex items-center gap-2 text-sm font-medium">
              <CheckCircle size={18} weight="fill" className="text-accent" /> 저장했어요
            </p>
          )}
          <div className="mt-8 flex flex-wrap items-center gap-3">
            <ShareButton id={entry.id} />
            <button type="button" onClick={onHome} className={button}>
              <House size={16} /> 처음으로
            </button>
            {entry.mine && (
              <button type="button" onClick={remove} disabled={deleting === 'busy'} className={button}>
                <Trash size={16} /> 삭제
              </button>
            )}
          </div>
          {deleting === 'failed' && (
            <p role="alert" className="mt-3 text-sm text-accent">
              삭제하지 못했어요. 다시 시도해 주세요.
            </p>
          )}
        </motion.div>
        {alternatives.length > 0 && (
          <div className="md:pt-24">
            <p className="text-sm text-zinc-500">이것도 괜찮아요</p>
            <ul className="mt-4 divide-y divide-zinc-200 border-y border-zinc-200">
              {alternatives.map((food) => (
                <li key={food.id} className="py-5">
                  <p className="text-xl font-semibold tracking-tight">{food.name}</p>
                  <p className="mt-1 text-sm leading-relaxed text-zinc-500">{food.description}</p>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
      {places && (
        <section className="mt-20 border-t border-zinc-200 pt-12">
          <p className="flex items-center gap-2 text-sm text-zinc-500">
            <MapPin size={16} className="text-accent" /> {places.origin.name} 기준
          </p>
          <div className="mt-6">
            <PlaceTable food={best.name} nearby={places.nearby} famous={places.famous} />
          </div>
          {places.dateCourses.length > 0 && (
            <div className="mt-16">
              <DateCourses courses={places.dateCourses} />
            </div>
          )}
        </section>
      )}
    </div>
  )
}

function ViewSkeleton() {
  return (
    <div className="animate-pulse" aria-hidden>
      <div className="h-4 w-40 rounded-full bg-zinc-200" />
      <div className="mt-6 h-12 w-56 rounded-2xl bg-zinc-200 md:h-[4.5rem] md:w-80" />
      <div className="mt-6 h-4 w-72 max-w-full rounded-full bg-zinc-200" />
      <div className="mt-8 h-11 w-64 max-w-full rounded-full bg-zinc-200" />
    </div>
  )
}
```

- [ ] **Step 9: Add the saved list**

`frontend/src/features/saved/SavedList.tsx`:

```tsx
import { House, SignIn, Trash } from '@phosphor-icons/react'
import { motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { deleteSaved, fetchMySaved, savedDate } from './api.ts'
import type { SavedSummary } from './api.ts'

type State = { status: 'loading' | 'out' | 'error' } | { status: 'done'; items: SavedSummary[] }

type Props = {
  onOpen: (id: string) => void
  onHome: () => void
}

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

const button =
  'flex w-fit items-center gap-2 rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]'

export default function SavedList({ onOpen, onHome }: Props) {
  const [state, setState] = useState<State>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [deleteFailed, setDeleteFailed] = useState(false)

  useEffect(() => {
    let current = true
    fetchMySaved().then(
      (items) => current && setState(items ? { status: 'done', items } : { status: 'out' }),
      () => current && setState({ status: 'error' }),
    )
    return () => {
      current = false
    }
  }, [attempt])

  function remove(id: string) {
    if (!confirm('이 결과를 지울까요? 링크도 더 이상 열리지 않아요.')) return
    setDeleteFailed(false)
    deleteSaved(id).then(
      () => setState((prev) => (prev.status === 'done' ? { status: 'done', items: prev.items.filter((item) => item.id !== id) } : prev)),
      () => setDeleteFailed(true),
    )
  }

  return (
    <div className="w-full max-w-3xl">
      <p className="text-sm font-medium text-accent">저장한 결과</p>
      {state.status === 'loading' && <ListSkeleton />}
      {state.status === 'out' && (
        <div className="mt-6">
          <p className="text-2xl font-semibold tracking-tight">로그인하면 저장한 결과를 볼 수 있어요.</p>
          <a href="/oauth2/authorization/kakao" className={`mt-8 ${button}`}>
            <SignIn size={16} /> 카카오로 로그인
          </a>
        </div>
      )}
      {state.status === 'error' && (
        <div className="mt-6">
          <p className="text-2xl font-semibold tracking-tight">저장한 결과를 불러오지 못했어요.</p>
          <button
            type="button"
            onClick={() => {
              setState({ status: 'loading' })
              setAttempt((n) => n + 1)
            }}
            className="mt-8 rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98]"
          >
            다시 시도
          </button>
        </div>
      )}
      {state.status === 'done' && state.items.length === 0 && (
        <div className="mt-6">
          <p className="text-2xl font-semibold tracking-tight">아직 저장한 결과가 없어요.</p>
          <p className="mt-2 text-base text-zinc-500">메뉴를 뽑고 저장해 보세요.</p>
        </div>
      )}
      {state.status === 'done' && state.items.length > 0 && (
        <ul className="mt-6 divide-y divide-zinc-200 border-y border-zinc-200">
          {state.items.map((item, index) => (
            <motion.li
              key={item.id}
              initial={{ opacity: 0, y: 12 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ ...spring, delay: index * 0.05 }}
              className="flex items-center gap-2"
            >
              <button
                type="button"
                onClick={() => onOpen(item.id)}
                className="min-w-0 flex-1 py-5 text-left transition hover:bg-zinc-100/60"
              >
                <span className="block truncate text-lg font-semibold tracking-tight">{item.foodName}</span>
                <span className="mt-1 block truncate text-sm text-zinc-500">
                  {[item.title, item.originName && `${item.originName} 기준`].filter(Boolean).join(' · ')}
                </span>
              </button>
              <span className="shrink-0 font-mono text-sm text-zinc-500">{savedDate(item.createdAt)}</span>
              <button
                type="button"
                aria-label={`${item.foodName} 삭제`}
                onClick={() => remove(item.id)}
                className="shrink-0 rounded-full p-3 text-zinc-500 transition hover:bg-zinc-100 hover:text-zinc-900 active:scale-[0.98]"
              >
                <Trash size={18} />
              </button>
            </motion.li>
          ))}
        </ul>
      )}
      {deleteFailed && (
        <p role="alert" className="mt-3 text-sm text-accent">
          삭제하지 못했어요. 다시 시도해 주세요.
        </p>
      )}
      <button type="button" onClick={onHome} className={`mt-8 ${button}`}>
        <House size={16} /> 처음으로
      </button>
    </div>
  )
}

function ListSkeleton() {
  return (
    <div className="mt-6 animate-pulse divide-y divide-zinc-200" aria-hidden>
      {[0, 1, 2].map((row) => (
        <div key={row} className="flex items-center justify-between py-5">
          <div className="h-5 w-40 rounded-full bg-zinc-200" />
          <div className="h-4 w-16 rounded-full bg-zinc-200" />
        </div>
      ))}
    </div>
  )
}
```

- [ ] **Step 10: Add `저장한 결과` to the account menu**

`frontend/src/features/account/AccountMenu.tsx`, whole file after the change:

```tsx
import { BookmarksSimple, CaretDown, SignIn, SignOut } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useState } from 'react'
import { fetchMe, logout } from './api.ts'
import type { Me } from './api.ts'

type Account = { status: 'checking' } | { status: 'out' } | { status: 'in'; me: Me }

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

// onOpenSaved comes from App: features don't import each other
export default function AccountMenu({ onOpenSaved }: { onOpenSaved: () => void }) {
  const [account, setAccount] = useState<Account>({ status: 'checking' })
  const [open, setOpen] = useState(false)
  const [loginFailed] = useState(() => new URLSearchParams(location.search).get('login') === 'failed')
  const [logoutFailed, setLogoutFailed] = useState(false)

  useEffect(() => {
    // the server being down reads as logged out; the login link still works once it is back
    fetchMe().then(
      (me) => setAccount(me ? { status: 'in', me } : { status: 'out' }),
      () => setAccount({ status: 'out' }),
    )
  }, [])

  useEffect(() => {
    if (!loginFailed) return
    const url = new URL(location.href)
    url.searchParams.delete('login')
    history.replaceState(null, '', url)
  }, [loginFailed])

  function signOut() {
    setLogoutFailed(false)
    logout().then(
      () => {
        setOpen(false)
        setAccount({ status: 'out' })
      },
      () => setLogoutFailed(true),
    )
  }

  return (
    <div className="absolute right-4 top-4 flex flex-col items-end md:right-12 md:top-6">
      {account.status === 'checking' && <div className="h-10 w-36 animate-pulse rounded-full bg-zinc-100" aria-hidden />}
      {account.status === 'out' && (
        <a
          href="/oauth2/authorization/kakao"
          className="flex h-10 items-center gap-2 rounded-full border border-zinc-300 bg-white px-4 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]"
        >
          <SignIn size={18} />
          카카오로 로그인
        </a>
      )}
      {account.status === 'in' && (
        <div className="relative">
          <button
            type="button"
            aria-expanded={open}
            onClick={() => setOpen((value) => !value)}
            className="flex h-10 max-w-[14rem] items-center gap-2 rounded-full border border-zinc-300 bg-white px-4 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]"
          >
            <span className="truncate">{account.me.nickname}님</span>
            <motion.span animate={{ rotate: open ? 180 : 0 }} transition={spring} className="flex">
              <CaretDown size={14} />
            </motion.span>
          </button>
          <AnimatePresence>
            {open && (
              <motion.div
                initial={{ opacity: 0, y: -4 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -4 }}
                transition={spring}
                className="absolute right-0 mt-2 rounded-2xl border border-zinc-200 bg-white p-1"
              >
                <button
                  type="button"
                  onClick={() => {
                    setOpen(false)
                    onOpenSaved()
                  }}
                  className="flex w-full items-center gap-2 whitespace-nowrap rounded-xl px-4 py-2 text-sm text-zinc-700 transition hover:bg-zinc-100 active:scale-[0.98]"
                >
                  <BookmarksSimple size={16} />
                  저장한 결과
                </button>
                <button
                  type="button"
                  onClick={signOut}
                  className="flex w-full items-center gap-2 whitespace-nowrap rounded-xl px-4 py-2 text-sm text-zinc-700 transition hover:bg-zinc-100 active:scale-[0.98]"
                >
                  <SignOut size={16} />
                  로그아웃
                </button>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      )}
      {loginFailed && account.status === 'out' && (
        <p role="alert" className="mt-2 text-sm text-accent">
          로그인하지 못했어요. 다시 시도해 주세요.
        </p>
      )}
      {logoutFailed && account.status === 'in' && (
        <p role="alert" className="mt-2 text-sm text-accent">
          로그아웃하지 못했어요. 다시 시도해 주세요.
        </p>
      )}
    </div>
  )
}
```

- [ ] **Step 11: Wire the pages in `App.tsx`**

`frontend/src/App.tsx`:

```tsx
import { AnimatePresence, motion } from 'motion/react'
import { useEffect, useRef, useState } from 'react'
import AccountMenu from './features/account/AccountMenu.tsx'
import ModeSelect from './features/mode-select/ModeSelect.tsx'
import PlacesSection from './features/places/PlacesSection.tsx'
import { fetchRandom } from './features/random/api.ts'
import { fetchRecommendation } from './features/recommendation/api.ts'
import type { Recommendation } from './features/recommendation/api.ts'
import Questionnaire from './features/recommendation/Questionnaire.tsx'
import type { Answers } from './features/recommendation/questions.ts'
import { resumePending } from './features/saved/pending.ts'
import SaveButton from './features/saved/SaveButton.tsx'
import SavedList from './features/saved/SavedList.tsx'
import SavedView from './features/saved/SavedView.tsx'
import type { Food } from './shared/api.ts'
import type { Places } from './shared/places/types.ts'
import ResultView from './shared/ResultView.tsx'

type Screen = 'home' | 'random' | 'survey' | 'recommend'

// the saved screens have their own address; everything else lives at /
type Page = { kind: 'main' } | { kind: 'list' } | { kind: 'view'; id: string }

type Result =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'done'; best: Food; alternatives: Food[] }

function pageOf(path: string): Page {
  const view = path.match(/^\/s\/([\w-]+)$/)
  if (view) return { kind: 'view', id: view[1] }
  return path === '/saved' ? { kind: 'list' } : { kind: 'main' }
}

export default function App() {
  const [page, setPage] = useState<Page>(() => pageOf(location.pathname))
  const [screen, setScreen] = useState<Screen>('home')
  const [result, setResult] = useState<Result>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [places, setPlaces] = useState<{ data: Places | null; version: number }>({ data: null, version: 0 })
  const [justSaved, setJustSaved] = useState<string | null>(null)
  const [saveFailed, setSaveFailed] = useState(false)
  const latest = useRef(0)
  const lastRequest = useRef<() => void>(() => {})

  useEffect(() => {
    const sync = () => setPage(pageOf(location.pathname))
    addEventListener('popstate', sync)
    return () => removeEventListener('popstate', sync)
  }, [])

  useEffect(() => {
    // back from the Kakao login with a result the visitor wanted to save
    resumePending()?.then(
      (id) => {
        setJustSaved(id)
        go(`/s/${id}`)
      },
      () => setSaveFailed(true),
    )
  }, [])

  function go(path: string) {
    history.pushState(null, '', path)
    setPage(pageOf(path))
  }

  function run(next: Screen, fetcher: () => Promise<Recommendation>) {
    // responses from an older request (or after going home) are ignored
    const id = ++latest.current
    lastRequest.current = () => run(next, fetcher)
    setScreen(next)
    setAttempt(id)
    setResult({ status: 'loading' })
    setPlaces((prev) => ({ data: null, version: prev.version + 1 }))
    fetcher().then(
      (data) => id === latest.current && setResult({ status: 'done', ...data }),
      () => id === latest.current && setResult({ status: 'error' }),
    )
  }

  function drawRandom() {
    run('random', () => fetchRandom().then((food) => ({ best: food, alternatives: [] })))
  }

  function recommend(answers: Answers) {
    run('recommend', () => fetchRecommendation(answers))
  }

  function goHome() {
    latest.current++
    setScreen('home')
    setSaveFailed(false)
    if (page.kind !== 'main') go('/')
  }

  const title = screen === 'random' ? '오늘의 랜덤 메뉴' : '당신에게 딱 맞는 메뉴'
  const key =
    page.kind === 'view' ? `view-${page.id}` : page.kind === 'list' ? 'list' : screen === 'home' || screen === 'survey' ? screen : `${screen}-${attempt}`

  return (
    <main className="relative mx-auto flex min-h-[100dvh] w-full max-w-6xl items-center px-4 pb-8 pt-20 md:px-12">
      <AccountMenu onOpenSaved={() => go('/saved')} />
      {saveFailed && page.kind === 'main' && screen === 'home' && (
        <p role="alert" className="absolute left-4 top-20 text-sm text-accent md:left-12">
          저장하지 못했어요. 다시 시도해 주세요.
        </p>
      )}
      <AnimatePresence mode="wait">
        <motion.div
          key={key}
          className="w-full"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.2 }}
        >
          {page.kind === 'list' && <SavedList onOpen={(id) => go(`/s/${id}`)} onHome={goHome} />}
          {page.kind === 'view' && (
            <SavedView id={page.id} justSaved={justSaved === page.id} onHome={goHome} onDeleted={() => go('/saved')} />
          )}
          {page.kind === 'main' && screen === 'home' && (
            <ModeSelect onRandom={drawRandom} onSurvey={() => setScreen('survey')} />
          )}
          {page.kind === 'main' && screen === 'survey' && <Questionnaire onComplete={recommend} onExit={goHome} />}
          {page.kind === 'main' && (screen === 'random' || screen === 'recommend') && (
            <>
              <ResultView
                title={title}
                status={result.status}
                best={result.status === 'done' ? result.best : undefined}
                alternatives={result.status === 'done' ? result.alternatives : []}
                roll={screen === 'random'}
                againLabel={screen === 'random' ? '다시 뽑기' : '다시 하기'}
                onAgain={screen === 'random' ? drawRandom : () => setScreen('survey')}
                onRetry={() => lastRequest.current()}
                onHome={goHome}
              />
              {result.status === 'done' && (
                <>
                  <SaveButton
                    key={places.version}
                    saved={{ title, best: result.best, alternatives: result.alternatives, places: places.data }}
                  />
                  <PlacesSection
                    food={result.best.name}
                    onLoaded={(data) => setPlaces((prev) => ({ data, version: prev.version + 1 }))}
                  />
                </>
              )}
            </>
          )}
        </motion.div>
      </AnimatePresence>
    </main>
  )
}
```

- [ ] **Step 12: Update `CLAUDE.md`**

Replace the feature list under Layout with:

```markdown
- Backend: `com.example.pickyourfood.<feature>` — `food` (catalog + tags), `random`, `recommendation`, `place` (restaurant info via Kakao + Google), `account` (Kakao login, sessions), `saved` (saved results and share links).
- Frontend: `frontend/src/features/<feature>/` — `mode-select`, `random`, `recommendation`, `places`, `account`, `saved`.
- Only code used by two or more features goes in `food` (backend) or `frontend/src/shared/` (e.g. `shared/places/`: the place tables and date courses). Features must not import from each other.
```

- [ ] **Step 13: Type check, build and lint**

Run: `cd frontend && npm run build && npm run lint`
Expected: `✓ built`, no type errors, no lint errors.

- [ ] **Step 14: Run every browser scenario**

```bash
for s in save pending pending-failed view missing mine list empty mobile; do node <workspace>/saved-check.mjs $s; done
```

Expected: 30 `PASS` lines and no `FAIL`.

Then look at the screenshots saved next to the script (`saved-view.png`, `saved-list.png`, `saved-mobile-*.png`):
- Google columns show `—`.
- The date is in the mono font.
- Nothing overflows at 375px.

- [ ] **Step 15: Commit**

```bash
git add frontend/src CLAUDE.md
git commit -m "feat: save, list and share results with a pending save across login

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 16: Real save with Kakao login (user)**

1. With `KAKAO_REST_KEY` set, run `./gradlew bootRun` and `npm run dev`.
2. While logged out: draw → find places → `저장하기` → Kakao login.
3. You land back on `/s/{id}` showing `저장했어요`.
4. Open the link in a private window: the result shows and there is no delete button.
5. Delete the result from `저장한 결과`.
