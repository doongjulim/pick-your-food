# G2. 실제 도보 경로와 코스 지도 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 데이트 코스의 구간(식당 → 카페 → 볼거리)을 TMAP 보행자 경로로 계산하고, 그 경로를 카카오맵 지도에 그린다. 저장·공유 링크에서도 같은 지도가 보인다.

**Architecture:** 서버의 `place` 기능이 새 `TmapClient`로 구간마다 TMAP을 병렬 호출한다. 결과는 `Leg.path`(`[lat, lng]` 목록, 최대 200점)에 담기고, 실패하면 지금처럼 직선거리로 돌아간다. 화면은 `shared/places/`의 새 `CourseMap`이 Kakao Maps JS SDK로 핀과 선을 그린다. JS 키는 `GET /api/places/map-key`로 받는다. 저장 결과는 `path`를 그대로 보관하고 검증한다.

**Tech Stack:** Spring Boot 4.1.1, Java 17, Jackson 3, `RestClient` + `MockRestServiceServer`, Mockito, React + Vite + Tailwind v4, Kakao Maps JS SDK(스크립트 태그, npm 의존성 없음).

**Spec:** `docs/superpowers/specs/2026-10-03-walking-routes-design.md`

## Global Constraints

- Gradle 명령 앞에는 항상 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- Jackson 3: databind는 `tools.jackson.databind.*`, 어노테이션은 `com.fasterxml.jackson.annotation.*`.
- package-by-feature. 백엔드 변경은 `place`, `saved`(+ `account/SecurityConfig`의 공개 경로 한 줄). 프론트는 `frontend/src/shared/places/`. 기능끼리 import 금지(`saved`는 `place`의 상수를 쓰지 않고 숫자 200을 직접 쓴다).
- 비밀 값은 환경변수로만: `TMAP_APP_KEY`, `KAKAO_JS_KEY`. 저장소에 키를 넣지 않는다.
- 경로 점: 소수점 5자리, 구간당 최대 200점(`MAX_PATH_POINTS = 200`), 양 끝은 유지.
- 도보 시간: TMAP이면 `max(1, ceil(totalTime / 60))`, 직선이면 기존 계산(67m/분, 올림, 최소 1).
- TMAP 캐시: 성공한 경로만, 메모리, 키 `"{fromId}>{toId}"`, 24시간(`CACHE_TTL`).
- 강조색 hex: `#ca5843`(Tailwind `oklch(0.6 0.15 32)`).
- 안내 문구: 모든 구간에 path가 있으면 없음 / 일부만 없으면 `일부 구간은 직선거리 기준이에요` / 모두 없으면 `직선거리 기준 예상 시간이에요`.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. `.claude/`, `.serena/`, `graphify-out/`, `data/`, `.superpowers/`는 커밋하지 않는다.
- 이모지 금지. 아이콘은 `@phosphor-icons/react`.

## Review Focus

1. **TMAP이 느리거나 죽어 있을 때** — 검색이 구간 수만큼 느려지면 안 된다. 구간 호출은 병렬이고 `HttpTimeouts`(연결 2초, 읽기 3초)가 상한이다. 실패는 직선으로 돌아가고 검색은 200이다. 테스트: Task 2 `failedRouteFallsBackToTheStraightLineAndIsNotCached`.
2. **이상한 TMAP 응답**(features 없음, 합계 없음, 좌표가 숫자 두 개가 아님) — 잘못된 선을 그리지 말고 직선으로 돌아가야 한다. 테스트: Task 1 `unusableResponsesThrow`.
3. **조작된 저장 요청의 path**(201점, 범위 밖 좌표, 점이 숫자 3개나 1개, null 점) — 400이고 저장되지 않는다. 테스트: Task 3 `walkingPathsAreKeptAndCheckedOnSave`.
4. **path가 없는 예전 저장 결과** — 공유 링크가 그대로 열리고 지도는 점선으로 그린다. 서버 테스트: Task 3(기존 `RESULT`에 path 없음 확인), 화면: Task 4 헤드리스 확인의 `path` 없는 구간.
5. **JS 키가 없거나 SDK 로드 실패** — 지도 자리만 사라지고 나머지 화면은 그대로다. 테스트: Task 2 `mapKeyIsNullWithoutAKey`, Task 4 헤드리스 확인(키 null).

---

### Task 1: TmapClient와 설정·문서

**Files:**
- Create: `src/main/java/com/example/pickyourfood/place/TmapClient.java`
- Create: `src/test/java/com/example/pickyourfood/place/TmapClientTest.java`
- Modify: `src/main/resources/application.properties`
- Modify: `render.yaml`
- Modify: `README.md`

**Interfaces:**
- Produces: `public class TmapClient` (`@Component`) with
  - `public Optional<Route> route(double fromLat, double fromLng, double toLat, double toLng)` — 키가 비면 `Optional.empty()`(요청 없음). HTTP 오류·파싱 실패는 `RestClientException`.
  - `public record Route(int meters, int seconds, List<double[]> path)` — `path`는 `[lat, lng]` 점, 2점 이상.
  - 테스트용 생성자 `TmapClient(RestClient.Builder builder, String key)`.
- 스펙의 `startName`/`endName`은 TMAP 필수 값이지만 경로에 영향이 없어 고정 ASCII `"start"`/`"end"`를 보낸다(한글 URL 인코딩 문제를 피함).

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/pickyourfood/place/TmapClientTest.java`:

```java
package com.example.pickyourfood.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.pickyourfood.place.TmapClient.Route;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class TmapClientTest {

	private static final String URL = "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1";

	// trimmed from a real answer: totals on the first feature, two lines that share a joint point, a turn point between
	private static final String ROUTE = """
			{"type":"FeatureCollection","features":[
			{"type":"Feature","geometry":{"type":"Point","coordinates":[127.05,37.56]},"properties":{"totalDistance":480,"totalTime":372,"pointType":"SP"}},
			{"type":"Feature","geometry":{"type":"LineString","coordinates":[[127.05,37.56],[127.051,37.561]]},"properties":{"distance":200}},
			{"type":"Feature","geometry":{"type":"Point","coordinates":[127.051,37.561]},"properties":{"pointType":"GP"}},
			{"type":"Feature","geometry":{"type":"LineString","coordinates":[[127.051,37.561],[127.052,37.564]]},"properties":{"distance":280}}]}""";

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final TmapClient tmap = new TmapClient(builder, "test-key");

	@Test
	void walkingRouteJoinsTheLinesAsLatLng() {
		server.expect(requestTo(URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("appKey", "test-key"))
				.andExpect(jsonPath("$.startX").value(127.05))
				.andExpect(jsonPath("$.startY").value(37.56))
				.andExpect(jsonPath("$.endX").value(127.052))
				.andExpect(jsonPath("$.endY").value(37.564))
				.andExpect(jsonPath("$.reqCoordType").value("WGS84GEO"))
				.andExpect(jsonPath("$.resCoordType").value("WGS84GEO"))
				.andRespond(withSuccess(ROUTE, MediaType.APPLICATION_JSON));

		Route route = tmap.route(37.56, 127.05, 37.564, 127.052).orElseThrow();

		assertThat(route.meters()).isEqualTo(480);
		assertThat(route.seconds()).isEqualTo(372);
		assertThat(route.path()).containsExactly(
				new double[] { 37.56, 127.05 }, new double[] { 37.561, 127.051 }, new double[] { 37.564, 127.052 });
		server.verify();
	}

	@Test
	void unusableResponsesThrow() {
		String noFeatures = "{\"type\":\"FeatureCollection\",\"features\":[]}";
		String noTotals = "{\"features\":[{\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[127.05,37.56],[127.051,37.561]]},\"properties\":{}}]}";
		String badPoint = "{\"features\":[{\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[127.05],[127.051,37.561]]},"
				+ "\"properties\":{\"totalDistance\":1,\"totalTime\":1}}]}";
		String noLine = "{\"features\":[{\"geometry\":{\"type\":\"Point\",\"coordinates\":[127.05,37.56]},"
				+ "\"properties\":{\"totalDistance\":0,\"totalTime\":0}}]}";
		for (String body : new String[] { noFeatures, noTotals, badPoint, noLine }) {
			server.reset();
			server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
			assertThatThrownBy(() -> tmap.route(37.56, 127.05, 37.564, 127.052)).as(body).isInstanceOf(RestClientException.class);
		}
	}

	@Test
	void serverErrorThrows() {
		server.expect(requestTo(URL)).andRespond(withServerError());

		assertThatThrownBy(() -> tmap.route(37.56, 127.05, 37.564, 127.052)).isInstanceOf(RestClientException.class);
	}

	@Test
	void withoutAKeyNothingIsSent() {
		RestClient.Builder unused = RestClient.builder();
		MockRestServiceServer silent = MockRestServiceServer.bindTo(unused).build();

		assertThat(new TmapClient(unused, "").route(37.56, 127.05, 37.564, 127.052)).isEmpty();
		silent.verify();
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests '*TmapClientTest'`
Expected: 컴파일 실패 (`cannot find symbol: class TmapClient`).

- [ ] **Step 3: 구현**

`src/main/java/com/example/pickyourfood/place/TmapClient.java`:

```java
package com.example.pickyourfood.place;

import com.example.pickyourfood.food.HttpTimeouts;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

// TMAP pedestrian routes (SK open API)
@Component
public class TmapClient {

	private final RestClient http;
	private final String key;

	@Autowired
	public TmapClient(@Value("${tmap.app-key:}") String key) {
		this(RestClient.builder().requestFactory(HttpTimeouts.factory()), key);
	}

	TmapClient(RestClient.Builder builder, String key) {
		this.http = builder.baseUrl("https://apis.openapi.sk.com").build();
		this.key = key;
	}

	// empty without a key; RestClientException when TMAP fails or answers something unusable
	public Optional<Route> route(double fromLat, double fromLng, double toLat, double toLng) {
		if (key.isBlank()) return Optional.empty();
		Response response = http.post()
				.uri("/tmap/routes/pedestrian?version=1")
				.header("appKey", key)
				.contentType(MediaType.APPLICATION_JSON)
				// the names are required labels only; ASCII avoids TMAP's URL-encoding rule for them
				.body(Map.of("startX", fromLng, "startY", fromLat, "endX", toLng, "endY", toLat,
						"startName", "start", "endName", "end",
						"reqCoordType", "WGS84GEO", "resCoordType", "WGS84GEO"))
				.retrieve()
				.body(Response.class);
		return Optional.of(parse(response));
	}

	// totals sit on the first feature; the LineStrings, in order, are the path as [lng, lat]
	private static Route parse(Response response) {
		if (response == null || response.features() == null || response.features().isEmpty()) {
			throw new RestClientException("TMAP: no route");
		}
		Properties totals = response.features().get(0).properties();
		if (totals == null || totals.totalDistance() == null || totals.totalTime() == null) {
			throw new RestClientException("TMAP: no totals");
		}
		List<double[]> path = new ArrayList<>();
		for (Feature feature : response.features()) {
			if (feature.geometry() == null || !"LineString".equals(feature.geometry().type())) continue;
			for (JsonNode point : feature.geometry().coordinates()) {
				if (point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) {
					throw new RestClientException("TMAP: bad point " + point);
				}
				double[] latLng = { point.get(1).asDouble(), point.get(0).asDouble() };
				// consecutive lines share their joint point
				if (path.isEmpty() || !Arrays.equals(path.get(path.size() - 1), latLng)) path.add(latLng);
			}
		}
		if (path.size() < 2) throw new RestClientException("TMAP: no line");
		return new Route(totals.totalDistance(), totals.totalTime(), path);
	}

	public record Route(int meters, int seconds, List<double[]> path) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Response(List<Feature> features) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Feature(Geometry geometry, Properties properties) {
	}

	// a Point's coordinates are one pair, a LineString's a list of pairs
	@JsonIgnoreProperties(ignoreUnknown = true)
	record Geometry(String type, JsonNode coordinates) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Properties(Integer totalDistance, Integer totalTime) {
	}
}
```

- [ ] **Step 4: 통과 확인**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests '*TmapClientTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: 설정과 문서**

`src/main/resources/application.properties` — `google.places-key=...` 줄 바로 아래에 추가:

```properties
tmap.app-key=${TMAP_APP_KEY:}
kakao.js-key=${KAKAO_JS_KEY:}
```

`render.yaml` — `GOOGLE_PLACES_KEY` 항목 바로 아래에 추가:

```yaml
      - key: TMAP_APP_KEY
        sync: false
      - key: KAKAO_JS_KEY
        sync: false
```

`README.md`:
- 12행 키 목록 끝(`GOOGLE_PLACES_KEY`(평점·리뷰, 선택).)을 다음으로 바꾼다:
  `` `GOOGLE_PLACES_KEY`(평점·리뷰, 선택), `TMAP_APP_KEY`(데이트 코스의 실제 도보 경로, 없으면 직선거리), `KAKAO_JS_KEY`(코스 지도, 없으면 지도를 숨김). ``
- 배포 3단계 목록 끝(`KAKAO_ADMIN_KEY` 줄 아래)에 추가:
  ```markdown
     - `TMAP_APP_KEY`: [SK open API](https://openapi.sk.com)에서 앱을 만들고 TMAP API를 사용 신청한 뒤 받은 appKey
     - `KAKAO_JS_KEY`: 카카오 개발자 콘솔 → 앱 → 앱 키 → JavaScript 키. 브라우저에 그대로 노출되는 키라 등록한 도메인에서만 동작한다
  ```
- 4단계 아래에 새 줄 추가(이후 번호는 그대로 5):
  ```markdown
     카카오 개발자 콘솔 → 플랫폼 → Web 사이트 도메인에 `http://localhost:5173`, `http://localhost:8080`, `https://<서비스 이름>.onrender.com`을 등록하고, 앱 설정에서 카카오맵 사용을 켠다.
  ```

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/example/pickyourfood/place/TmapClient.java src/test/java/com/example/pickyourfood/place/TmapClientTest.java src/main/resources/application.properties render.yaml README.md
git commit -m "$(cat <<'EOF'
feat: add the TMAP pedestrian route client

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: PlaceService 도보 경로, Leg.path, 지도 키 API

**Files:**
- Modify: `src/main/java/com/example/pickyourfood/place/PlacesResponse.java:32-34`
- Modify: `src/main/java/com/example/pickyourfood/place/PlaceService.java`
- Modify: `src/main/java/com/example/pickyourfood/place/PlaceController.java`
- Modify: `src/main/java/com/example/pickyourfood/account/SecurityConfig.java:19`
- Test: `src/test/java/com/example/pickyourfood/place/PlaceServiceTest.java`
- Test: `src/test/java/com/example/pickyourfood/place/PlaceControllerTest.java`

**Interfaces:**
- Consumes (Task 1): `TmapClient.route(double fromLat, double fromLng, double toLat, double toLng): Optional<Route>`, `TmapClient.Route(int meters, int seconds, List<double[]> path)`.
- Produces:
  - `PlacesResponse.Leg(String from, String to, int meters, int walkMinutes, List<double[]> path)` — `path`는 null 가능. JSON: `"path":[[lat,lng],…]` 또는 `"path":null`.
  - `PlaceService(KakaoClient kakao, GooglePlacesClient google, TmapClient tmap)`.
  - `static final int MAX_PATH_POINTS = 200`, `static List<double[]> thin(List<double[]> path)`.
  - `GET /api/places/map-key` → `{"key":"…"}` 또는 `{"key":null}`, 로그인 불필요.

- [ ] **Step 1: 실패하는 테스트 작성**

`PlaceServiceTest.java`:

1. import 추가:
```java
import com.example.pickyourfood.place.TmapClient.Route;
import java.util.stream.IntStream;
```
2. 필드를 바꾼다:
```java
	private final KakaoClient kakao = mock(KakaoClient.class);
	private final GooglePlacesClient google = mock(GooglePlacesClient.class);
	// a mock answers Optional.empty(), so tests that don't stub it get straight-line legs
	private final TmapClient tmap = mock(TmapClient.class);
	private final PlaceService service = new PlaceService(kakao, google, tmap);
```
3. 기존 단언 두 곳에 `, null`을 붙인다:
```java
		assertThat(course.legs()).containsExactly(new Leg("r1", "cafe", 445, 7, null), new Leg("cafe", "sight", 556, 9, null));
```
```java
		assertThat(course.legs()).containsExactly(new Leg("r1", "sight", 0, 1, null));
```
4. 새 테스트(파일 끝, 마지막 `}` 앞):
```java
	private static final List<double[]> WALK = List.of(
			new double[] { 37.56, 127.05 }, new double[] { 37.562, 127.051 }, new double[] { 37.564, 127.05 });

	@Test
	void legsFollowTheWalkingRouteWhenTmapHasOne() {
		famousRanked("r1");
		cafesNear(37.56, spot("cafe", 37.564));
		when(tmap.route(37.56, 127.05, 37.564, 127.05)).thenReturn(Optional.of(new Route(512, 421, WALK)));

		Leg leg = service.search("ramen", ORIGIN).dateCourses().get(0).legs().get(0);

		assertThat(leg.meters()).isEqualTo(512);
		assertThat(leg.walkMinutes()).isEqualTo(8); // 421s rounds up
		assertThat(leg.path()).containsExactlyElementsOf(WALK);
	}

	@Test
	void failedRouteFallsBackToTheStraightLineAndIsNotCached() {
		famousRanked("r1");
		cafesNear(37.56, spot("cafe", 37.564)); // 445m from the restaurant
		when(tmap.route(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenThrow(new RestClientException("TMAP down"));

		service.search("ramen", ORIGIN);
		DateCourse course = service.search("ramen", ORIGIN).dateCourses().get(0);

		assertThat(course.legs()).containsExactly(new Leg("r1", "cafe", 445, 7, null));
		verify(tmap, times(2)).route(anyDouble(), anyDouble(), anyDouble(), anyDouble());
	}

	@Test
	void routesAreCachedPerPairOfStops() {
		famousRanked("r1");
		cafesNear(37.56, spot("cafe", 37.564));
		when(tmap.route(37.56, 127.05, 37.564, 127.05)).thenReturn(Optional.of(new Route(512, 421, WALK)));

		service.search("ramen", ORIGIN);
		Leg leg = service.search("ramen", ORIGIN).dateCourses().get(0).legs().get(0);

		assertThat(leg.meters()).isEqualTo(512);
		verify(tmap, times(1)).route(anyDouble(), anyDouble(), anyDouble(), anyDouble());
	}

	@Test
	void pathsAreRoundedAndLongOnesThinnedKeepingBothEnds() {
		assertThat(PlaceService.thin(List.of(new double[] { 37.123456789, 127.987654321 })))
				.containsExactly(new double[] { 37.12346, 127.98765 });

		List<double[]> longPath = IntStream.range(0, 1000).mapToObj(i -> new double[] { 37.5 + i * 0.000001234, 127.0 }).toList();
		List<double[]> thinned = PlaceService.thin(longPath);

		assertThat(thinned).hasSize(PlaceService.MAX_PATH_POINTS);
		assertThat(thinned.get(0)).containsExactly(37.5, 127.0);
		assertThat(thinned.get(thinned.size() - 1)).containsExactly(37.50123, 127.0); // the 1000th point, rounded
	}
```

`PlaceControllerTest.java`:

1. import 추가:
```java
import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.pickyourfood.place.PlacesResponse.DateCourse;
import com.example.pickyourfood.place.PlacesResponse.Leg;
import com.example.pickyourfood.place.PlacesResponse.Place;
```
2. 클래스 어노테이션을 `@SpringBootTest(properties = "kakao.js-key=test-js-key")`로 바꾼다.
3. 새 테스트:
```java
	@Test
	void legPathIsSentAsLatLngPairs() throws Exception {
		Origin here = new Origin("현재 위치", 37.54, 127.05);
		Place restaurant = new Place("r1", "r1", "", 0, 37.56, 127.05, "", null, null, null, List.of(), null);
		Leg leg = new Leg("r1", "cafe", 512, 8, List.of(new double[] { 37.56, 127.05 }, new double[] { 37.564, 127.051 }));
		Leg straight = new Leg("cafe", "sight", 300, 5, null);
		DateCourse course = new DateCourse(restaurant, null, null, List.of(leg, straight), "https://map.kakao.com/link/by/walk/a");
		when(places.search("탄탄멘", here)).thenReturn(new PlacesResponse(here, List.of(), List.of(), List.of(course)));

		mvc.perform(get("/api/places").param("food", "탄탄멘").param("lat", "37.54").param("lng", "127.05"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dateCourses[0].legs[0].path[1][0]").value(37.564))
				.andExpect(jsonPath("$.dateCourses[0].legs[0].path[1][1]").value(127.051))
				.andExpect(jsonPath("$.dateCourses[0].legs[1].path").value(nullValue()));
	}

	@Test
	void mapKeyNeedsNoLogin() throws Exception {
		mvc.perform(get("/api/places/map-key"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.key").value("test-js-key"));
	}

	@Test
	void mapKeyIsNullWithoutAKey() {
		assertThat(new PlaceController(places, "").mapKey().key()).isNull();
	}
```

- [ ] **Step 2: 실패 확인**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests '*PlaceServiceTest' --tests '*PlaceControllerTest'`
Expected: 컴파일 실패 (`Leg` 생성자 인자 수, `PlaceService` 생성자, `thin`, `mapKey`).

- [ ] **Step 3: 구현**

`PlacesResponse.java` — `Leg`와 주석을 바꾼다:

```java
	// walking distance and time between two stops: TMAP's route, with path its [lat, lng] points, or when that is
	// unavailable the straight line, with path null; walkMinutes rounds up to at least 1
	public record Leg(String from, String to, int meters, int walkMinutes, List<double[]> path) {
	}
```

`PlaceService.java`:

1. import 추가: `import com.example.pickyourfood.place.TmapClient.Route;`
2. 상수(`CACHE_TTL` 아래):
```java
	// per leg, so three saved courses stay far under the saved result's size limit
	static final int MAX_PATH_POINTS = 200;
```
3. 필드와 생성자:
```java
	private final KakaoClient kakao;
	private final GooglePlacesClient google;
	private final TmapClient tmap;
	// ponytail: unbounded map, entries only expire when read again; swap for Caffeine if memory grows
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();
	// successful routes only, so a TMAP outage is retried on the next search
	private final Map<String, CachedRoute> routes = new ConcurrentHashMap<>();
```
(`pool` 필드는 그대로)
```java
	PlaceService(KakaoClient kakao, GooglePlacesClient google, TmapClient tmap) {
		this.kakao = kakao;
		this.google = google;
		this.tmap = tmap;
	}
```
4. `dateCourses`의 루프와 반환을 바꾼다(모든 코스의 구간 요청이 동시에 나간다):
```java
		// picked in course order, so an earlier course keeps its nearest spot
		Set<String> used = new HashSet<>();
		List<CompletableFuture<DateCourse>> courses = new ArrayList<>();
		for (int i = 0; i < restaurants.size(); i++) {
			Spot cafe = pick(cafes.get(i).join(), used);
			Spot sight = pick(sights.get(i).join(), used);
			courses.add(courseAsync(restaurants.get(i), cafe, sight));
		}
		return courses.stream().map(CompletableFuture::join).toList();
```
5. `course`와 `leg`를 다음으로 바꾼다:
```java
	private CompletableFuture<DateCourse> courseAsync(Place restaurant, Spot cafe, Spot sight) {
		List<Stop> stops = new ArrayList<>();
		stops.add(new Stop(restaurant.id(), restaurant.name(), restaurant.lat(), restaurant.lng()));
		if (cafe != null) stops.add(new Stop(cafe.id(), cafe.name(), cafe.lat(), cafe.lng()));
		if (sight != null) stops.add(new Stop(sight.id(), sight.name(), sight.lat(), sight.lng()));
		List<CompletableFuture<Leg>> legs = IntStream.range(1, stops.size())
				.mapToObj(i -> legAsync(stops.get(i - 1), stops.get(i))).toList();
		String routeUrl = legs.isEmpty() ? null
				: ROUTE_BASE + stops.stream()
						.map(stop -> routeName(stop.name()) + "," + stop.lat() + "," + stop.lng())
						.collect(Collectors.joining("/"));
		return CompletableFuture.allOf(legs.toArray(CompletableFuture[]::new))
				.thenApply(done -> new DateCourse(restaurant, cafe, sight, legs.stream().map(CompletableFuture::join).toList(), routeUrl));
	}

	private CompletableFuture<Leg> legAsync(Stop from, Stop to) {
		return CompletableFuture.supplyAsync(() -> route(from, to)
				.map(found -> new Leg(from.name(), to.name(), found.meters(), Math.max(1, (int) Math.ceil(found.seconds() / 60.0)), found.path()))
				.orElseGet(() -> {
					int meters = meters(from.lat(), from.lng(), to.lat(), to.lng());
					int minutes = Math.max(1, (int) Math.ceil(meters / (double) WALK_METERS_PER_MINUTE));
					return new Leg(from.name(), to.name(), meters, minutes, null);
				}), pool);
	}

	// TMAP's walking route, thinned; empty without a key or when TMAP fails, and the leg falls back to the straight line
	private Optional<Route> route(Stop from, Stop to) {
		String key = from.id() + ">" + to.id();
		CachedRoute cached = routes.get(key);
		if (cached != null && cached.at().plus(CACHE_TTL).isAfter(Instant.now())) return Optional.of(cached.route());
		try {
			Optional<Route> route = tmap.route(from.lat(), from.lng(), to.lat(), to.lng())
					.map(found -> new Route(found.meters(), found.seconds(), thin(found.path())));
			route.ifPresent(found -> routes.put(key, new CachedRoute(found, Instant.now())));
			return route;
		} catch (RuntimeException e) {
			log.warn("TMAP route failed from {} to {}: {}", from.name(), to.name(), e.getMessage());
			return Optional.empty();
		}
	}

	// 5 decimals is about 1m; a longer path keeps both ends and evenly spaced points between
	static List<double[]> thin(List<double[]> path) {
		int size = Math.min(path.size(), MAX_PATH_POINTS);
		return IntStream.range(0, size)
				.mapToObj(i -> path.get(size == path.size() ? i : (int) Math.round(i * (path.size() - 1) / (double) (size - 1))))
				.map(point -> new double[] { round5(point[0]), round5(point[1]) })
				.toList();
	}

	private static double round5(double value) {
		return Math.round(value * 100_000) / 100_000.0;
	}
```
6. 파일 끝의 레코드:
```java
	// id keys the route cache
	private record Stop(String id, String name, double lat, double lng) {
	}

	private record Cached(Optional<GoogleInfo> info, Instant at) {
	}

	private record CachedRoute(Route route, Instant at) {
	}
```

`PlaceController.java`:

```java
package com.example.pickyourfood.place;

import com.example.pickyourfood.place.PlacesResponse.Origin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class PlaceController {

	private final PlaceService places;
	private final String mapKey;

	PlaceController(PlaceService places, @Value("${kakao.js-key:}") String mapKey) {
		this.places = places;
		this.mapKey = mapKey;
	}
```
(`places(...)` 핸들러는 그대로 두고, 그 아래에 추가)
```java
	// the Kakao Maps JavaScript key is public by design (Kakao only honours it on our domains); null hides the map
	@GetMapping("/api/places/map-key")
	MapKey mapKey() {
		return new MapKey(mapKey.isBlank() ? null : mapKey);
	}

	record MapKey(String key) {
	}
}
```

`SecurityConfig.java:19`:

```java
						.requestMatchers("/api/foods/**", "/api/recommendations", "/api/places", "/api/places/map-key").permitAll()
```

- [ ] **Step 4: 통과 확인**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests '*PlaceServiceTest' --tests '*PlaceControllerTest' --tests '*TmapClientTest'`
Expected: PASS.

- [ ] **Step 5: 전체 테스트**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test`
Expected: 전체 PASS. `saved`는 자기 `SavedResult.Leg` 레코드를 쓰므로 아직 영향이 없다.

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/example/pickyourfood/place src/main/java/com/example/pickyourfood/account/SecurityConfig.java src/test/java/com/example/pickyourfood/place
git commit -m "$(cat <<'EOF'
feat: walk the date course legs along TMAP routes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 저장 결과의 Leg.path와 검증

**Files:**
- Modify: `src/main/java/com/example/pickyourfood/saved/SavedResult.java:23-24`
- Modify: `src/main/java/com/example/pickyourfood/saved/SavedController.java:75-79`
- Test: `src/test/java/com/example/pickyourfood/saved/SavedControllerTest.java`

**Interfaces:**
- Consumes: 화면이 보내는 `legs[].path`(`[[lat,lng],…]` 또는 null/없음). 형태는 Task 2의 `PlacesResponse.Leg` JSON과 같다.
- Produces: `SavedResult.Leg(String from, String to, int meters, int walkMinutes, List<double[]> path)`. 공유 링크 응답에 `path`가 그대로 나간다.

- [ ] **Step 1: 실패하는 테스트 작성**

`SavedControllerTest.java`:

1. `savedResultGetsAnUnguessableLinkAndDropsGoogleAndUnknownFields`의 `walkMinutes` 단언 아래에 한 줄 추가(path가 없는 예전 결과도 읽힌다):
```java
				.andExpect(jsonPath("$.result.places.dateCourses[0].legs[0].path").isEmpty())
```
2. `repeat(...)` 메서드 아래에 새 테스트와 도우미:
```java
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
```

- [ ] **Step 2: 실패 확인**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests '*SavedControllerTest'`
Expected: `walkingPathsAreKeptAndCheckedOnSave` FAIL — `path`가 저장되지 않아 `No value at JSON path "$.result.places.dateCourses[0].legs[0].path[1][0]"`.

- [ ] **Step 3: 구현**

`SavedResult.java`:

```java
	// path is the walking route as [lat, lng] points; null for straight-line legs and results saved before routes
	record Leg(String from, String to, int meters, int walkMinutes, List<double[]> path) {
	}
```

`SavedController.java` — `valid(SavedResult.Places)`와 새 메서드:

```java
	private static boolean valid(SavedResult.Places p) {
		return p.origin() != null && p.origin().name() != null && p.origin().name().length() <= 200
				&& fits(p.nearby(), 10) && fits(p.famous(), 10) && fits(p.dateCourses(), 3)
				&& p.dateCourses().stream().allMatch(c -> c.restaurant() != null && fits(c.legs(), 2)
						&& c.legs().stream().allMatch(leg -> leg.path() == null || validPath(leg.path())));
	}

	// 200 matches the points the server thins a route to; each point is [lat, lng]
	private static boolean validPath(List<double[]> path) {
		return fits(path, 200) && path.stream()
				.allMatch(point -> point.length == 2 && Math.abs(point[0]) <= 90 && Math.abs(point[1]) <= 180);
	}
```

- [ ] **Step 4: 통과 확인**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test`
Expected: 전체 PASS.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/pickyourfood/saved src/test/java/com/example/pickyourfood/saved
git commit -m "$(cat <<'EOF'
feat: keep walking paths in saved results

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 코스 지도 화면

**Files:**
- Modify: `frontend/src/shared/places/types.ts:34-40`
- Create: `frontend/src/shared/places/kakaoMap.ts`
- Create: `frontend/src/shared/places/CourseMap.tsx`
- Modify: `frontend/src/shared/places/DateCourses.tsx`

**Interfaces:**
- Consumes: `GET /api/places/map-key` → `{ key: string | null }`(Task 2), `Leg.path`(Task 2·3). 예전 저장 결과에는 `path` 키가 아예 없을 수 있으므로 코드는 `leg.path`를 참/거짓으로만 검사한다(`=== null` 금지).
- Produces: `loadKakaoMaps(): Promise<KakaoMaps | null>`, `<CourseMap course={DateCourse} />`.
- `features/saved/api.ts`는 바꾸지 않는다(공유 `DateCourse` 타입을 쓰고 legs를 그대로 넘긴다).

- [ ] **Step 1: 타입**

`types.ts`의 `Leg`:

```ts
// walking distance between two stops; path is the walking route as [lat, lng] points,
// null (or missing in older saved results) when only the straight line is known
export type Leg = {
  from: string
  to: string
  meters: number
  walkMinutes: number
  path: [number, number][] | null
}
```

- [ ] **Step 2: SDK 로더**

`frontend/src/shared/places/kakaoMap.ts`:

```ts
import { request } from '../api.ts'

// just the parts of the Kakao Maps SDK the course map uses
type LatLng = object
type Bounds = { extend: (point: LatLng) => void }
type Layer = { setMap: (map: KakaoMap | null) => void }

export type KakaoMap = {
  setBounds: (bounds: Bounds) => void
  addControl: (control: object, position: number) => void
}

export type KakaoMaps = {
  load: (ready: () => void) => void
  Map: new (container: HTMLElement, options: { center: LatLng; level: number; draggable: boolean; scrollwheel: boolean }) => KakaoMap
  LatLng: new (lat: number, lng: number) => LatLng
  LatLngBounds: new () => Bounds
  Polyline: new (options: { path: LatLng[]; strokeWeight: number; strokeColor: string; strokeOpacity: number; strokeStyle: string }) => Layer
  CustomOverlay: new (options: { position: LatLng; content: HTMLElement; yAnchor: number }) => Layer
  ZoomControl: new () => object
  ControlPosition: { RIGHT: number }
}

declare global {
  interface Window {
    kakao?: { maps: KakaoMaps }
  }
}

let loading: Promise<KakaoMaps | null> | undefined

// loads the SDK once per page; null when the server has no key or loading fails, and the map stays hidden
export function loadKakaoMaps(): Promise<KakaoMaps | null> {
  loading ??= request<{ key: string | null }>('/api/places/map-key')
    .then(({ key }) =>
      key
        ? new Promise<KakaoMaps | null>((resolve, reject) => {
            const script = document.createElement('script')
            script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(key)}&autoload=false`
            script.onload = () => {
              const maps = window.kakao?.maps
              if (maps) maps.load(() => resolve(maps))
              else resolve(null)
            }
            script.onerror = reject
            document.head.append(script)
          })
        : null,
    )
    .catch(() => null)
  return loading
}
```

- [ ] **Step 3: 지도 컴포넌트**

`frontend/src/shared/places/CourseMap.tsx`:

```tsx
import { useEffect, useRef, useState } from 'react'
import type { DateCourse } from './types.ts'
import { loadKakaoMaps } from './kakaoMap.ts'
import type { KakaoMap, KakaoMaps } from './kakaoMap.ts'

// the accent color, oklch(0.6 0.15 32), in a form the SDK takes
const ACCENT = '#ca5843'

// numbered like the timeline: the cafe is 02 and the sight 03 even when a step is missing
function pin(number: number): HTMLElement {
  const element = document.createElement('div')
  element.className =
    'flex size-7 items-center justify-center rounded-full bg-accent font-mono text-xs font-semibold text-white ring-2 ring-white shadow-[0_4px_12px_-4px_rgba(0,0,0,0.4)]'
  element.textContent = String(number).padStart(2, '0')
  return element
}

// the course on a Kakao map: numbered pins, solid lines along walking routes, dashed straight lines without one
export default function CourseMap({ course }: { course: DateCourse }) {
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<KakaoMap | null>(null)
  // undefined while loading, null when there is no map to show
  const [maps, setMaps] = useState<KakaoMaps | null>()

  useEffect(() => {
    let mounted = true
    loadKakaoMaps().then((loaded) => mounted && setMaps(loaded))
    return () => {
      mounted = false
    }
  }, [])

  useEffect(() => {
    if (!maps || !container.current) return
    const stops = [
      { number: 1, place: course.restaurant },
      { number: 2, place: course.cafe },
      { number: 3, place: course.sight },
    ].flatMap(({ number, place }) => (place ? [{ number, at: new maps.LatLng(place.lat, place.lng) }] : []))
    if (!map.current) {
      map.current = new maps.Map(container.current, { center: stops[0].at, level: 4, draggable: false, scrollwheel: false })
      map.current.addControl(new maps.ZoomControl(), maps.ControlPosition.RIGHT)
    }
    // legs join only the stops that exist, in order
    const lines = course.legs.map((leg, index) =>
      leg.path
        ? new maps.Polyline({
            path: leg.path.map(([lat, lng]) => new maps.LatLng(lat, lng)),
            strokeWeight: 4, strokeColor: ACCENT, strokeOpacity: 0.9, strokeStyle: 'solid',
          })
        : new maps.Polyline({
            path: [stops[index].at, stops[index + 1].at],
            strokeWeight: 3, strokeColor: ACCENT, strokeOpacity: 0.9, strokeStyle: 'shortdash',
          }),
    )
    const pins = stops.map(({ number, at }) => new maps.CustomOverlay({ position: at, content: pin(number), yAnchor: 0.5 }))
    const bounds = new maps.LatLngBounds()
    stops.forEach(({ at }) => bounds.extend(at))
    course.legs.forEach((leg) => leg.path?.forEach(([lat, lng]) => bounds.extend(new maps.LatLng(lat, lng))))
    const layers = [...lines, ...pins]
    layers.forEach((layer) => layer.setMap(map.current))
    map.current.setBounds(bounds)
    return () => layers.forEach((layer) => layer.setMap(null))
  }, [maps, course])

  if (maps === null) return null
  return (
    <div
      ref={container}
      aria-label="코스 지도"
      className={`mt-8 h-60 overflow-hidden rounded-3xl border border-zinc-200 bg-zinc-100 md:h-80 ${maps ? '' : 'animate-pulse'}`}
    />
  )
}
```

- [ ] **Step 4: DateCourses에 넣기**

`DateCourses.tsx`:

1. import 추가: `import CourseMap from './CourseMap.tsx'`
2. `steps` 선언 아래에 추가:
```tsx
  const straight = legs.filter((leg) => !leg.path).length
  const note = straight === 0 ? null : straight === legs.length ? '직선거리 기준 예상 시간이에요' : '일부 구간은 직선거리 기준이에요'
```
3. 탭을 바꿔도 지도를 다시 만들지 않도록, 애니메이션은 타임라인(`ol`)에만 건다. `<AnimatePresence mode="wait">`부터 `</AnimatePresence>`까지(61–118행)를 다음으로 바꾼다. `ol` 안의 `steps.map(...)`은 지금 코드 그대로 옮긴다:
```tsx
      <AnimatePresence mode="wait">
        <motion.ol
          key={selected}
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -8 }}
          transition={spring}
          className="mt-8 grid gap-8 md:grid-cols-3 md:gap-6"
        >
          {steps.map((step, index) => (
            // 지금 DateCourses.tsx 71–101행의 <motion.li> … </motion.li>를 한 글자도 바꾸지 않고 옮긴다
          ))}
        </motion.ol>
      </AnimatePresence>
      {/* a restaurant-only course has nothing to draw */}
      {legs.length > 0 && <CourseMap course={courses[selected]} />}
      {routeUrl && (
        <div className="mt-8 flex flex-wrap items-center gap-x-4 gap-y-2">
          <a
            href={routeUrl}
            target="_blank"
            rel="noreferrer"
            className="flex w-fit items-center gap-2 rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98]"
          >
            <MapTrifold size={16} /> 전체 경로 보기
          </a>
          {note && <p className="text-sm text-zinc-500">{note}</p>}
        </div>
      )}
```

- [ ] **Step 5: 타입 검사와 빌드**

Run: `cd frontend && npm run build && npm run lint`
Expected: `tsc -b`와 `vite build`가 오류 없이 끝나고 oxlint 경고 0.

- [ ] **Step 6: 헤드리스 확인**

`npx vite --port 5174 --strictPort`를 띄우고(백엔드 없음), 헤드리스 Chrome(`--headless=new --remote-debugging-port=9222 --user-data-dir=<scratch>`)을 CDP로 조종한다. `Fetch` 도메인으로 다음을 가로챈다:

- `*/api/places/map-key` → `{"key":"test"}`
- `*/api/places*`(검색) → 코스 2개짜리 응답. 코스 1: 식당·카페·볼거리, 첫 구간 `path` 3점, 둘째 구간 `path` 없음(키 자체를 뺀다). 코스 2: 두 구간 모두 `path` 3점.
- `https://dapi.kakao.com/*` → 아래 가짜 SDK(JavaScript). 호출을 `window.__calls`에 기록한다.
  ```js
  window.__calls = []
  window.kakao = { maps: {
    load: (cb) => cb(),
    Map: function (el, o) { window.__calls.push(['Map', o.draggable, o.scrollwheel]); el.style.background = '#e4e4e7'; this.setBounds = () => window.__calls.push(['setBounds']); this.addControl = () => {} },
    LatLng: function (lat, lng) { this.lat = lat; this.lng = lng },
    LatLngBounds: function () { this.extend = () => {} },
    Polyline: function (o) { window.__calls.push(['Polyline', o.strokeStyle, o.path.length, o.strokeColor]); this.setMap = (m) => window.__calls.push(['Polyline.setMap', m ? 'on' : 'off']) },
    CustomOverlay: function (o) { window.__calls.push(['Pin', o.content.textContent]); this.setMap = () => {} },
    ZoomControl: function () {},
    ControlPosition: { RIGHT: 3 },
  } }
  ```

확인할 것:
1. 코스 1: `Map`이 한 번(`draggable false`, `scrollwheel false`), `Polyline solid 3 #ca5843`과 `Polyline shortdash 2 #ca5843`, 핀 `01 02 03`, `setBounds`. 문구 `일부 구간은 직선거리 기준이에요`.
2. 코스 2 탭 클릭: `Map`이 더 생기지 않고, 이전 선은 `setMap off`, 새 선은 둘 다 `solid`. 안내 문구 없음.
3. `map-key`를 `{"key":null}`로 바꿔 새로 연 페이지: `[aria-label="코스 지도"]` 없음, 타임라인과 `전체 경로 보기`는 그대로.
4. 스크린샷 두 장(데스크톱 1280px, 모바일 390px)을 scratchpad에 저장하고 지도 자리·간격을 눈으로 확인.

끝나면 Chrome과 Vite를 `pkill`로 끈다.

- [ ] **Step 7: 커밋**

```bash
git add frontend/src/shared/places
git commit -m "$(cat <<'EOF'
feat: draw the date course on a Kakao map

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```
