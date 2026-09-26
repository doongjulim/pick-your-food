# Restaurant Info (Sub-project B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Under every A result screen, show nearby (1km) and famous (20km) restaurants for the picked food with price/rating/review comparison, plus a meal → cafe → sight date course.

**Architecture:** A new backend feature package `place` exposes `GET /api/places`; it calls Kakao Local (search) and Google Places Text Search (ratings, price, reviews) with `RestClient`, enriches up to 10 places in parallel with a 24h in-memory cache, and builds the date course. A new frontend feature folder `features/places/` renders the location picker, sortable comparison table and course timeline; `App.tsx` composes it under `ResultView`.

**Tech Stack:** Spring Boot 4.1.1 (spring-web `RestClient`, Jackson 3), JUnit 5 + `MockRestServiceServer` + Mockito + MockMvc; React 19 + TypeScript 6 + Tailwind v4 + `motion` + `@phosphor-icons/react`.

**Spec:** `docs/superpowers/specs/2026-09-26-restaurant-info-design.md`

## Global Constraints

- Every Gradle command runs with `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` (default JDK is 11).
- Package by feature (CLAUDE.md): backend code in `com.example.pickyourfood.place`, frontend code in `frontend/src/features/places/`; only cross-feature code in `frontend/src/shared/`. Features never import each other; `App.tsx` composes them.
- No new dependencies (backend or frontend).
- Keys: `kakao.rest-key=${KAKAO_REST_KEY:}`, `google.places-key=${GOOGLE_PLACES_KEY:}`; the app must start without them.
- Radii and sizes: nearby 1,000m / 5 places, famous 20,000m / 5 places, Google match ≤ 200m, cafe `CE7` ≤ 500m, sight `AT4` ≤ 1,000m, cache 24h, external timeouts connect 2s / read 3s, at most 10 Google calls per search.
- Errors: missing `food` or location → 400; unknown `near` → 404; Kakao key missing or Kakao failure → 502; Google failure → 200 with Google fields `null`.
- Design: zinc base + single `accent`, Phosphor icons, no emojis, spring motion `{ type: 'spring', stiffness: 100, damping: 20 }`, `min-h-[100dvh]` layout already in `App.tsx`, no horizontal scroll at 375px.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never commit `.claude/`, `.serena/`, `graphify-out/`.

## Review Focus

1. Hangul in `food`/`near` (e.g. `탄탄멘`, `성수동`) → percent-encoded on the Kakao request, not mangled. Pinned by `KakaoClientTest.koreanQueryIsPercentEncoded` (Task 1).
2. Kakao documents with an empty `road_address_name` or empty `distance` (typed place names, category hits) → fall back to lot address and 0m, no `NumberFormatException`. Pinned by `KakaoClientTest.fallsBackToLotAddressAndZeroDistance` (Task 1).
3. Google places with no rating/price/reviews, or reviews without `text` → `null` fields and "정보 없음", never a 500. Pinned by `GooglePlacesClientTest.placeWithoutOptionalFieldsHasNulls` and the review filter in `sendsTextSearchAndParsesPlace` (Task 2).
4. `lat` without `lng`, blank `food`, blank `near` → 400, not 500 or a Kakao call. Pinned by `PlaceControllerTest.missingFoodOrLocationIsBadRequest` (Task 4).
5. Geolocation denied or unavailable → notice under the input and focus moves to it; a slower earlier search never overwrites a newer one. Pinned by the `denied` browser scenario (Task 6) and the `latest` request-id guard in `PlacesSection`.

---

### Task 1: Kakao client

**Files:**
- Modify: `src/main/resources/application.properties`
- Create: `src/main/java/com/example/pickyourfood/place/HttpTimeouts.java`
- Create: `src/main/java/com/example/pickyourfood/place/KakaoClient.java`
- Test: `src/test/java/com/example/pickyourfood/place/KakaoClientTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `KakaoClient.restaurants(String query, double lat, double lng, int radius, String sort, int size) → List<KakaoPlace>` (`sort` is `"distance"` or `"accuracy"`)
  - `KakaoClient.nearest(String category, double lat, double lng, int radius) → Optional<KakaoPlace>`
  - `KakaoClient.locate(String query) → Optional<KakaoPlace>`
  - constants `KakaoClient.RESTAURANT = "FD6"`, `CAFE = "CE7"`, `SIGHT = "AT4"`
  - `record KakaoClient.KakaoPlace(String id, String name, String category, String address, double lat, double lng, int distanceMeters, String url)`
  - all three methods throw `ResponseStatusException(BAD_GATEWAY)` on a missing key or any Kakao HTTP/IO failure
  - `HttpTimeouts.factory() → SimpleClientHttpRequestFactory` (2s connect, 3s read)

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/pickyourfood/place/KakaoClientTest.java`:

```java
package com.example.pickyourfood.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.pickyourfood.place.KakaoClient.KakaoPlace;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class KakaoClientTest {

	private static final String ONE_PLACE = """
			{"documents":[{"id":"26338954","place_name":"탄탄면공방","category_name":"음식점 > 중식 > 중국요리",
			"address_name":"서울 성동구 성수동2가 1","road_address_name":"서울 성동구 연무장길 1","x":"127.0567","y":"37.5445",
			"distance":"820","place_url":"http://place.map.kakao.com/26338954","phone":""}],"meta":{"total_count":1}}""";

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final KakaoClient kakao = new KakaoClient(builder, "test-key");

	@Test
	void restaurantsSendsKeywordSearchAndParsesPlaces() {
		server.expect(requestTo(containsString("https://dapi.kakao.com/v2/local/search/keyword.json")))
				.andExpect(header("Authorization", "KakaoAK test-key"))
				.andExpect(queryParam("query", "ramen"))
				.andExpect(queryParam("category_group_code", "FD6"))
				.andExpect(queryParam("x", "127.05"))
				.andExpect(queryParam("y", "37.54"))
				.andExpect(queryParam("radius", "1000"))
				.andExpect(queryParam("sort", "distance"))
				.andExpect(queryParam("size", "5"))
				.andRespond(withSuccess(ONE_PLACE, MediaType.APPLICATION_JSON));

		List<KakaoPlace> places = kakao.restaurants("ramen", 37.54, 127.05, 1000, "distance", 5);

		assertThat(places).containsExactly(new KakaoPlace("26338954", "탄탄면공방", "중국요리", "서울 성동구 연무장길 1",
				37.5445, 127.0567, 820, "http://place.map.kakao.com/26338954"));
		server.verify();
	}

	@Test
	void koreanQueryIsPercentEncoded() {
		server.expect(requestTo(containsString("query=%ED%83%84%ED%83%84%EB%A9%98")))
				.andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));

		assertThat(kakao.restaurants("탄탄멘", 37.54, 127.05, 1000, "distance", 5)).isEmpty();
		server.verify();
	}

	@Test
	void nearestSendsCategorySearchSortedByDistance() {
		server.expect(requestTo(containsString("https://dapi.kakao.com/v2/local/search/category.json")))
				.andExpect(queryParam("category_group_code", "CE7"))
				.andExpect(queryParam("x", "127.05"))
				.andExpect(queryParam("y", "37.54"))
				.andExpect(queryParam("radius", "500"))
				.andExpect(queryParam("sort", "distance"))
				.andExpect(queryParam("size", "1"))
				.andRespond(withSuccess(ONE_PLACE, MediaType.APPLICATION_JSON));

		assertThat(kakao.nearest(KakaoClient.CAFE, 37.54, 127.05, 500)).map(KakaoPlace::id).hasValue("26338954");
		server.verify();
	}

	@Test
	void fallsBackToLotAddressAndZeroDistance() {
		server.expect(requestTo(containsString("/v2/local/search/keyword.json")))
				.andExpect(queryParam("query", "seongsu"))
				.andExpect(queryParam("size", "1"))
				.andRespond(withSuccess("""
						{"documents":[{"id":"1","place_name":"성수동","category_name":"지역","address_name":"서울 성동구 성수동",
						"road_address_name":"","x":"127.0557","y":"37.5445","distance":"","place_url":"http://place.map.kakao.com/1"}]}""",
						MediaType.APPLICATION_JSON));

		assertThat(kakao.locate("seongsu")).hasValueSatisfying(place -> {
			assertThat(place.address()).isEqualTo("서울 성동구 성수동");
			assertThat(place.distanceMeters()).isZero();
			assertThat(place.lat()).isEqualTo(37.5445);
			assertThat(place.lng()).isEqualTo(127.0557);
		});
	}

	@Test
	void noDocumentsIsEmpty() {
		server.expect(requestTo(containsString("/v2/local/search/keyword.json")))
				.andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));

		assertThat(kakao.locate("nowhere")).isEmpty();
	}

	@Test
	void kakaoFailureIsBadGateway() {
		server.expect(requestTo(containsString("/v2/local/search/keyword.json"))).andRespond(withServerError());

		assertThatThrownBy(() -> kakao.locate("seongsu")).isInstanceOfSatisfying(ResponseStatusException.class,
				e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
	}

	@Test
	void missingKeyIsBadGatewayWithoutCallingKakao() {
		KakaoClient noKey = new KakaoClient(builder, "");

		assertThatThrownBy(() -> noKey.locate("seongsu")).isInstanceOfSatisfying(ResponseStatusException.class,
				e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
		server.verify();
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.place.KakaoClientTest'`
Expected: FAIL — compilation error, `cannot find symbol ... KakaoClient`.

- [ ] **Step 3: Write minimal implementation**

Append to `src/main/resources/application.properties`:

```properties
kakao.rest-key=${KAKAO_REST_KEY:}
google.places-key=${GOOGLE_PLACES_KEY:}
```

`src/main/java/com/example/pickyourfood/place/HttpTimeouts.java`:

```java
package com.example.pickyourfood.place;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

final class HttpTimeouts {

	private HttpTimeouts() {
	}

	static SimpleClientHttpRequestFactory factory() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(2));
		factory.setReadTimeout(Duration.ofSeconds(3));
		return factory;
	}
}
```

`src/main/java/com/example/pickyourfood/place/KakaoClient.java`:

```java
package com.example.pickyourfood.place;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriBuilder;

@Component
public class KakaoClient {

	static final String RESTAURANT = "FD6";
	static final String CAFE = "CE7";
	static final String SIGHT = "AT4";

	private final RestClient http;
	private final String key;

	@Autowired
	public KakaoClient(@Value("${kakao.rest-key:}") String key) {
		this(RestClient.builder().requestFactory(HttpTimeouts.factory()), key);
	}

	KakaoClient(RestClient.Builder builder, String key) {
		this.http = builder.baseUrl("https://dapi.kakao.com").build();
		this.key = key;
	}

	public List<KakaoPlace> restaurants(String query, double lat, double lng, int radius, String sort, int size) {
		return search(uri -> uri.path("/v2/local/search/keyword.json")
				.queryParam("query", query)
				.queryParam("category_group_code", RESTAURANT)
				.queryParam("x", lng)
				.queryParam("y", lat)
				.queryParam("radius", radius)
				.queryParam("sort", sort)
				.queryParam("size", size)
				.build());
	}

	public Optional<KakaoPlace> nearest(String category, double lat, double lng, int radius) {
		return search(uri -> uri.path("/v2/local/search/category.json")
				.queryParam("category_group_code", category)
				.queryParam("x", lng)
				.queryParam("y", lat)
				.queryParam("radius", radius)
				.queryParam("sort", "distance")
				.queryParam("size", 1)
				.build()).stream().findFirst();
	}

	// turns a typed place such as "성수동" into coordinates
	public Optional<KakaoPlace> locate(String query) {
		return search(uri -> uri.path("/v2/local/search/keyword.json")
				.queryParam("query", query)
				.queryParam("size", 1)
				.build()).stream().findFirst();
	}

	private List<KakaoPlace> search(Function<UriBuilder, URI> uri) {
		if (key.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "KAKAO_REST_KEY is not set");
		try {
			Response response = http.get().uri(uri).header("Authorization", "KakaoAK " + key).retrieve().body(Response.class);
			if (response == null || response.documents() == null) return List.of();
			return response.documents().stream().map(Document::toPlace).toList();
		} catch (RestClientException e) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Kakao request failed", e);
		}
	}

	public record KakaoPlace(String id, String name, String category, String address, double lat, double lng, int distanceMeters, String url) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Response(List<Document> documents) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Document(
			String id,
			@JsonProperty("place_name") String placeName,
			@JsonProperty("category_name") String categoryName,
			@JsonProperty("address_name") String addressName,
			@JsonProperty("road_address_name") String roadAddressName,
			String x,
			String y,
			String distance,
			@JsonProperty("place_url") String placeUrl) {

		KakaoPlace toPlace() {
			// "음식점 > 중식 > 중국요리" -> "중국요리"
			String category = categoryName == null ? "" : categoryName.substring(categoryName.lastIndexOf('>') + 1).strip();
			String address = roadAddressName == null || roadAddressName.isBlank() ? addressName : roadAddressName;
			// distance is empty when the search had no x/y
			int meters = distance == null || distance.isBlank() ? 0 : Integer.parseInt(distance);
			return new KakaoPlace(id, placeName, category, address, Double.parseDouble(y), Double.parseDouble(x), meters, placeUrl);
		}
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.place.KakaoClientTest'`
Expected: PASS — 7 tests, 0 failures.

- [ ] **Step 5: Run the whole suite (the app must still start without keys)**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test`
Expected: BUILD SUCCESSFUL — 21 tests (14 existing + 7), 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/application.properties src/main/java/com/example/pickyourfood/place src/test/java/com/example/pickyourfood/place
git commit -m "feat: add Kakao Local client for place search

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Google Places client and response types

**Files:**
- Create: `src/main/java/com/example/pickyourfood/place/PlacesResponse.java`
- Create: `src/main/java/com/example/pickyourfood/place/GooglePlacesClient.java`
- Test: `src/test/java/com/example/pickyourfood/place/GooglePlacesClientTest.java`

**Interfaces:**
- Consumes: `HttpTimeouts.factory()` (Task 1).
- Produces:
  - `record PlacesResponse(Origin origin, List<Place> nearby, List<Place> famous, DateCourse dateCourse)` with nested
    - `record Origin(String name, double lat, double lng)`
    - `record Place(String id, String name, String address, int distanceMeters, double lat, double lng, String kakaoUrl, Double rating, Integer reviewCount, Integer priceLevel, List<Review> reviews, String googleUrl)`
    - `record Review(String author, Integer rating, String text, String when)`
    - `record Spot(String name, String category, String address, int distanceMeters, String kakaoUrl)`
    - `record DateCourse(Place restaurant, Spot cafe, Spot sight)`
  - `GooglePlacesClient.find(String text, double lat, double lng) → Optional<GoogleInfo>`: empty with no key or no result; HTTP errors propagate as `RestClientException`
  - `record GooglePlacesClient.GoogleInfo(double lat, double lng, Double rating, Integer reviewCount, Integer priceLevel, List<Review> reviews, String url)`
  - `GooglePlacesClient.toLevel(String) → Integer` (1–4 or null), `GooglePlacesClient.FIELDS`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/pickyourfood/place/GooglePlacesClientTest.java`:

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

import com.example.pickyourfood.place.GooglePlacesClient.GoogleInfo;
import com.example.pickyourfood.place.PlacesResponse.Review;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class GooglePlacesClientTest {

	private static final String URL = "https://places.googleapis.com/v1/places:searchText";

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final GooglePlacesClient google = new GooglePlacesClient(builder, "g-key");

	@Test
	void sendsTextSearchAndParsesPlace() {
		server.expect(requestTo(URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("X-Goog-Api-Key", "g-key"))
				.andExpect(header("X-Goog-FieldMask", GooglePlacesClient.FIELDS))
				.andExpect(jsonPath("$.textQuery").value("탄탄면공방 서울 성동구 연무장길 1"))
				.andExpect(jsonPath("$.languageCode").value("ko"))
				.andExpect(jsonPath("$.pageSize").value(1))
				.andExpect(jsonPath("$.locationBias.circle.center.latitude").value(37.5445))
				.andExpect(jsonPath("$.locationBias.circle.center.longitude").value(127.0567))
				.andExpect(jsonPath("$.locationBias.circle.radius").value(200.0))
				.andRespond(withSuccess("""
						{"places":[{"location":{"latitude":37.5446,"longitude":127.0568},"rating":4.3,"userRatingCount":1284,
						"priceLevel":"PRICE_LEVEL_MODERATE","googleMapsUri":"https://maps.google.com/?cid=1",
						"reviews":[
						{"rating":5,"text":{"text":"국물이 진해요","languageCode":"ko"},"relativePublishTimeDescription":"2주 전","authorAttribution":{"displayName":"김서윤"}},
						{"rating":4,"text":{"text":"면이 쫄깃해요"},"relativePublishTimeDescription":"1달 전","authorAttribution":{"displayName":"박도현"}},
						{"rating":4,"originalText":{"text":"only original text"}},
						{"rating":3,"text":{"text":"웨이팅이 길어요"},"relativePublishTimeDescription":"3달 전"},
						{"rating":2,"text":{"text":"다섯 번째"},"relativePublishTimeDescription":"1년 전","authorAttribution":{"displayName":"이하준"}}]}]}""",
						MediaType.APPLICATION_JSON));

		GoogleInfo info = google.find("탄탄면공방 서울 성동구 연무장길 1", 37.5445, 127.0567).orElseThrow();

		assertThat(info.lat()).isEqualTo(37.5446);
		assertThat(info.lng()).isEqualTo(127.0568);
		assertThat(info.rating()).isEqualTo(4.3);
		assertThat(info.reviewCount()).isEqualTo(1284);
		assertThat(info.priceLevel()).isEqualTo(2);
		assertThat(info.url()).isEqualTo("https://maps.google.com/?cid=1");
		// the review without "text" is skipped, and at most 3 are kept
		assertThat(info.reviews()).containsExactly(
				new Review("김서윤", 5, "국물이 진해요", "2주 전"),
				new Review("박도현", 4, "면이 쫄깃해요", "1달 전"),
				new Review(null, 3, "웨이팅이 길어요", "3달 전"));
		server.verify();
	}

	@Test
	void placeWithoutOptionalFieldsHasNulls() {
		server.expect(requestTo(URL)).andRespond(withSuccess("""
				{"places":[{"location":{"latitude":37.5,"longitude":127.0}}]}""", MediaType.APPLICATION_JSON));

		GoogleInfo info = google.find("q", 37.5, 127.0).orElseThrow();

		assertThat(info.rating()).isNull();
		assertThat(info.reviewCount()).isNull();
		assertThat(info.priceLevel()).isNull();
		assertThat(info.reviews()).isEmpty();
		assertThat(info.url()).isNull();
	}

	@Test
	void noResultIsEmpty() {
		server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		assertThat(google.find("q", 37.5, 127.0)).isEmpty();
	}

	@Test
	void httpErrorPropagates() {
		server.expect(requestTo(URL)).andRespond(withServerError());

		assertThatThrownBy(() -> google.find("q", 37.5, 127.0)).isInstanceOf(RestClientException.class);
	}

	@Test
	void missingKeyIsEmptyWithoutCallingGoogle() {
		assertThat(new GooglePlacesClient(builder, "").find("q", 37.5, 127.0)).isEmpty();
		server.verify();
	}

	@Test
	void priceLevelsMapToOneThroughFour() {
		assertThat(GooglePlacesClient.toLevel("PRICE_LEVEL_INEXPENSIVE")).isEqualTo(1);
		assertThat(GooglePlacesClient.toLevel("PRICE_LEVEL_MODERATE")).isEqualTo(2);
		assertThat(GooglePlacesClient.toLevel("PRICE_LEVEL_EXPENSIVE")).isEqualTo(3);
		assertThat(GooglePlacesClient.toLevel("PRICE_LEVEL_VERY_EXPENSIVE")).isEqualTo(4);
		assertThat(GooglePlacesClient.toLevel("PRICE_LEVEL_FREE")).isNull();
		assertThat(GooglePlacesClient.toLevel("PRICE_LEVEL_UNSPECIFIED")).isNull();
		assertThat(GooglePlacesClient.toLevel(null)).isNull();
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.place.GooglePlacesClientTest'`
Expected: FAIL — compilation error, `cannot find symbol ... GooglePlacesClient`.

- [ ] **Step 3: Write minimal implementation**

`src/main/java/com/example/pickyourfood/place/PlacesResponse.java`:

```java
package com.example.pickyourfood.place;

import java.util.List;

public record PlacesResponse(Origin origin, List<Place> nearby, List<Place> famous, DateCourse dateCourse) {

	public record Origin(String name, double lat, double lng) {
	}

	// rating, reviewCount, priceLevel and googleUrl are null when Google has no matching place
	public record Place(
			String id,
			String name,
			String address,
			int distanceMeters,
			double lat,
			double lng,
			String kakaoUrl,
			Double rating,
			Integer reviewCount,
			Integer priceLevel,
			List<Review> reviews,
			String googleUrl) {
	}

	public record Review(String author, Integer rating, String text, String when) {
	}

	public record Spot(String name, String category, String address, int distanceMeters, String kakaoUrl) {
	}

	// cafe and sight are null when nothing is found near the restaurant
	public record DateCourse(Place restaurant, Spot cafe, Spot sight) {
	}
}
```

`src/main/java/com/example/pickyourfood/place/GooglePlacesClient.java`:

```java
package com.example.pickyourfood.place;

import com.example.pickyourfood.place.PlacesResponse.Review;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class GooglePlacesClient {

	static final String FIELDS =
			"places.location,places.rating,places.userRatingCount,places.priceLevel,places.reviews,places.googleMapsUri";

	private final RestClient http;
	private final String key;

	@Autowired
	public GooglePlacesClient(@Value("${google.places-key:}") String key) {
		this(RestClient.builder().requestFactory(HttpTimeouts.factory()), key);
	}

	GooglePlacesClient(RestClient.Builder builder, String key) {
		this.http = builder.baseUrl("https://places.googleapis.com").build();
		this.key = key;
	}

	// empty without a key or a result; HTTP errors propagate as RestClientException
	public Optional<GoogleInfo> find(String text, double lat, double lng) {
		if (key.isBlank()) return Optional.empty();
		Map<String, Object> body = Map.of(
				"textQuery", text,
				"languageCode", "ko",
				"pageSize", 1,
				"locationBias", Map.of("circle", Map.of(
						"center", Map.of("latitude", lat, "longitude", lng),
						"radius", 200.0)));
		Response response = http.post().uri("/v1/places:searchText")
				.header("X-Goog-Api-Key", key)
				.header("X-Goog-FieldMask", FIELDS)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(Response.class);
		if (response == null || response.places() == null) return Optional.empty();
		return response.places().stream().filter(place -> place.location() != null).findFirst().map(GooglePlace::toInfo);
	}

	static Integer toLevel(String priceLevel) {
		if (priceLevel == null) return null;
		return switch (priceLevel) {
			case "PRICE_LEVEL_INEXPENSIVE" -> 1;
			case "PRICE_LEVEL_MODERATE" -> 2;
			case "PRICE_LEVEL_EXPENSIVE" -> 3;
			case "PRICE_LEVEL_VERY_EXPENSIVE" -> 4;
			default -> null;
		};
	}

	public record GoogleInfo(double lat, double lng, Double rating, Integer reviewCount, Integer priceLevel, List<Review> reviews, String url) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Response(List<GooglePlace> places) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record GooglePlace(LatLng location, Double rating, Integer userRatingCount, String priceLevel, List<GoogleReview> reviews, String googleMapsUri) {

		GoogleInfo toInfo() {
			List<Review> kept = reviews == null ? List.of() : reviews.stream()
					.filter(review -> review.text() != null && review.text().text() != null)
					.limit(3)
					.map(review -> new Review(
							review.authorAttribution() == null ? null : review.authorAttribution().displayName(),
							review.rating(),
							review.text().text(),
							review.relativePublishTimeDescription()))
					.toList();
			return new GoogleInfo(location.latitude(), location.longitude(), rating, userRatingCount, toLevel(priceLevel), kept, googleMapsUri);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record LatLng(double latitude, double longitude) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record GoogleReview(Integer rating, Text text, String relativePublishTimeDescription, Author authorAttribution) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Text(String text) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Author(String displayName) {
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.place.GooglePlacesClientTest'`
Expected: PASS — 6 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pickyourfood/place src/test/java/com/example/pickyourfood/place
git commit -m "feat: add Google Places client and places response types

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Place service

**Files:**
- Create: `src/main/java/com/example/pickyourfood/place/PlaceService.java`
- Test: `src/test/java/com/example/pickyourfood/place/PlaceServiceTest.java`

**Interfaces:**
- Consumes: `KakaoClient` (Task 1), `GooglePlacesClient`, `PlacesResponse` types (Task 2).
- Produces:
  - `PlaceService.locate(String near) → Origin` (`Origin.name` = `near`; throws `ResponseStatusException(NOT_FOUND)` when Kakao has no match)
  - `PlaceService.search(String food, Origin origin) → PlacesResponse`
  - constants `NEARBY_RADIUS = 1_000`, `FAMOUS_RADIUS = 20_000`, `LIST_SIZE = 5`, `FAMOUS_CANDIDATES = 15`, `MATCH_METERS = 200`, `CAFE_RADIUS = 500`, `SIGHT_RADIUS = 1_000`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/pickyourfood/place/PlaceServiceTest.java`:

```java
package com.example.pickyourfood.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.pickyourfood.place.GooglePlacesClient.GoogleInfo;
import com.example.pickyourfood.place.KakaoClient.KakaoPlace;
import com.example.pickyourfood.place.PlacesResponse.Origin;
import com.example.pickyourfood.place.PlacesResponse.Place;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

class PlaceServiceTest {

	private static final Origin ORIGIN = new Origin("성수동", 37.54, 127.05);

	private final KakaoClient kakao = mock(KakaoClient.class);
	private final GooglePlacesClient google = mock(GooglePlacesClient.class);
	private final PlaceService service = new PlaceService(kakao, google);

	private static KakaoPlace place(String id, double lat) {
		return new KakaoPlace(id, id, "중국요리", "주소 " + id, lat, 127.05, 100, "https://place.map.kakao.com/" + id);
	}

	private static KakaoPlace place(String id) {
		return place(id, 37.54);
	}

	private static GoogleInfo info(double lat, Double rating, Integer reviewCount) {
		return new GoogleInfo(lat, 127.05, rating, reviewCount, 2, List.of(), "https://maps.google.com/?q=" + lat);
	}

	private void nearbyAre(KakaoPlace... places) {
		when(kakao.restaurants(eq("ramen"), anyDouble(), anyDouble(), eq(PlaceService.NEARBY_RADIUS), eq("distance"), eq(PlaceService.LIST_SIZE)))
				.thenReturn(List.of(places));
	}

	private void famousCandidatesAre(KakaoPlace... places) {
		when(kakao.restaurants(eq("ramen"), anyDouble(), anyDouble(), eq(PlaceService.FAMOUS_RADIUS), eq("accuracy"), eq(PlaceService.FAMOUS_CANDIDATES)))
				.thenReturn(List.of(places));
	}

	// the service searches Google with "<name> <address>"; names in these tests are the ids
	private void googleKnows(String id, GoogleInfo info) {
		when(google.find(startsWith(id + " "), anyDouble(), anyDouble())).thenReturn(Optional.of(info));
	}

	@Test
	void famousIsSortedByReviewCountThenRatingWithUnknownLast() {
		famousCandidatesAre(place("unknown"), place("few"), place("manyLow"), place("manyHigh"));
		googleKnows("few", info(37.54, 4.9, 10));
		googleKnows("manyLow", info(37.54, 4.0, 500));
		googleKnows("manyHigh", info(37.54, 4.5, 500));

		List<Place> famous = service.search("ramen", ORIGIN).famous();

		assertThat(famous).extracting(Place::id).containsExactly("manyHigh", "manyLow", "few", "unknown");
	}

	@Test
	void famousSkipsPlacesAlreadyNearbyAndKeepsFive() {
		nearbyAre(place("a"));
		famousCandidatesAre(place("a"), place("b"), place("c"), place("d"), place("e"), place("f"), place("g"));

		PlacesResponse response = service.search("ramen", ORIGIN);

		assertThat(response.nearby()).extracting(Place::id).containsExactly("a");
		assertThat(response.famous()).extracting(Place::id).containsExactlyInAnyOrder("b", "c", "d", "e", "f");
	}

	@Test
	void googleMatchFartherThan200mIsIgnored() {
		nearbyAre(place("close"), place("far"));
		googleKnows("close", info(37.541, 4.5, 100)); // about 111m away
		googleKnows("far", info(37.543, 4.5, 100)); // about 333m away

		List<Place> nearby = service.search("ramen", ORIGIN).nearby();

		assertThat(nearby.get(0).rating()).isEqualTo(4.5);
		assertThat(nearby.get(0).googleUrl()).isNotNull();
		assertThat(nearby.get(1).rating()).isNull();
		assertThat(nearby.get(1).reviewCount()).isNull();
		assertThat(nearby.get(1).priceLevel()).isNull();
		assertThat(nearby.get(1).reviews()).isEmpty();
		assertThat(nearby.get(1).googleUrl()).isNull();
	}

	@Test
	void googleFailureLeavesPlaceWithoutInfoAndIsRetriedNextTime() {
		nearbyAre(place("a"));
		when(google.find(startsWith("a "), anyDouble(), anyDouble()))
				.thenThrow(new RestClientException("boom"))
				.thenReturn(Optional.of(info(37.54, 4.2, 30)));

		assertThat(service.search("ramen", ORIGIN).nearby().get(0).rating()).isNull();
		assertThat(service.search("ramen", ORIGIN).nearby().get(0).rating()).isEqualTo(4.2);
	}

	@Test
	void cachedPlaceDoesNotCallGoogleAgain() {
		nearbyAre(place("a"));
		googleKnows("a", info(37.54, 4.2, 30));

		service.search("ramen", ORIGIN);
		PlacesResponse again = service.search("ramen", ORIGIN);

		assertThat(again.nearby().get(0).rating()).isEqualTo(4.2);
		verify(google, times(1)).find(startsWith("a "), anyDouble(), anyDouble());
	}

	@Test
	void dateCourseStartsAtTheTopFamousPlace() {
		famousCandidatesAre(place("second", 37.55), place("top", 37.56));
		googleKnows("second", info(37.55, 4.0, 10));
		googleKnows("top", info(37.56, 4.0, 900));
		when(kakao.nearest(KakaoClient.CAFE, 37.56, 127.05, PlaceService.CAFE_RADIUS))
				.thenReturn(Optional.of(new KakaoPlace("c", "어니언", "카페", "카페 주소", 37.561, 127.05, 320, "https://place.map.kakao.com/c")));

		PlacesResponse.DateCourse course = service.search("ramen", ORIGIN).dateCourse();

		assertThat(course.restaurant().id()).isEqualTo("top");
		assertThat(course.cafe().name()).isEqualTo("어니언");
		assertThat(course.cafe().distanceMeters()).isEqualTo(320);
		assertThat(course.sight()).isNull();
		verify(kakao).nearest(KakaoClient.SIGHT, 37.56, 127.05, PlaceService.SIGHT_RADIUS);
	}

	@Test
	void noFamousPlacesMeansNoDateCourse() {
		PlacesResponse response = service.search("ramen", ORIGIN);

		assertThat(response.famous()).isEmpty();
		assertThat(response.dateCourse()).isNull();
		verify(kakao, never()).nearest(anyString(), anyDouble(), anyDouble(), anyInt());
	}

	@Test
	void locateUsesTheFirstKakaoMatch() {
		when(kakao.locate("성수동")).thenReturn(Optional.of(place("seongsu", 37.5445)));

		assertThat(service.locate("성수동")).isEqualTo(new Origin("성수동", 37.5445, 127.05));
	}

	@Test
	void unknownPlaceIsNotFound() {
		assertThatThrownBy(() -> service.locate("없는동네")).isInstanceOfSatisfying(ResponseStatusException.class,
				e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.place.PlaceServiceTest'`
Expected: FAIL — compilation error, `cannot find symbol ... PlaceService`.

- [ ] **Step 3: Write minimal implementation**

`src/main/java/com/example/pickyourfood/place/PlaceService.java`:

```java
package com.example.pickyourfood.place;

import com.example.pickyourfood.place.GooglePlacesClient.GoogleInfo;
import com.example.pickyourfood.place.KakaoClient.KakaoPlace;
import com.example.pickyourfood.place.PlacesResponse.DateCourse;
import com.example.pickyourfood.place.PlacesResponse.Origin;
import com.example.pickyourfood.place.PlacesResponse.Place;
import com.example.pickyourfood.place.PlacesResponse.Spot;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlaceService {

	static final int NEARBY_RADIUS = 1_000;
	static final int FAMOUS_RADIUS = 20_000;
	static final int LIST_SIZE = 5;
	// Kakao's maximum page; the extra candidates replace ones already in the nearby list
	static final int FAMOUS_CANDIDATES = 15;
	static final int MATCH_METERS = 200;
	static final int CAFE_RADIUS = 500;
	static final int SIGHT_RADIUS = 1_000;
	static final Duration CACHE_TTL = Duration.ofHours(24);

	// most reviews first, then best rating; places Google does not know go last
	static final Comparator<Place> FAMOUS_ORDER = Comparator
			.comparing(Place::reviewCount, Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(Place::rating, Comparator.nullsLast(Comparator.reverseOrder()));

	private static final Logger log = LoggerFactory.getLogger(PlaceService.class);

	private final KakaoClient kakao;
	private final GooglePlacesClient google;
	// ponytail: unbounded map, entries only expire when read again; swap for Caffeine if memory grows
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();
	private final ExecutorService pool = Executors.newFixedThreadPool(10, task -> {
		Thread thread = new Thread(task, "google-places");
		thread.setDaemon(true);
		return thread;
	});

	PlaceService(KakaoClient kakao, GooglePlacesClient google) {
		this.kakao = kakao;
		this.google = google;
	}

	public Origin locate(String near) {
		KakaoPlace place = kakao.locate(near)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no place called " + near));
		return new Origin(near, place.lat(), place.lng());
	}

	public PlacesResponse search(String food, Origin origin) {
		List<KakaoPlace> near = kakao.restaurants(food, origin.lat(), origin.lng(), NEARBY_RADIUS, "distance", LIST_SIZE);
		Set<String> nearIds = near.stream().map(KakaoPlace::id).collect(Collectors.toSet());
		List<KakaoPlace> far = kakao.restaurants(food, origin.lat(), origin.lng(), FAMOUS_RADIUS, "accuracy", FAMOUS_CANDIDATES).stream()
				.filter(place -> !nearIds.contains(place.id()))
				.limit(LIST_SIZE)
				.toList();

		// start every Google lookup before waiting on any of them
		List<CompletableFuture<Place>> nearbyLookups = near.stream().map(this::enrichAsync).toList();
		List<CompletableFuture<Place>> famousLookups = far.stream().map(this::enrichAsync).toList();
		List<Place> nearby = nearbyLookups.stream().map(CompletableFuture::join).toList();
		List<Place> famous = famousLookups.stream().map(CompletableFuture::join).sorted(FAMOUS_ORDER).toList();

		return new PlacesResponse(origin, nearby, famous, dateCourse(famous));
	}

	private CompletableFuture<Place> enrichAsync(KakaoPlace place) {
		return CompletableFuture.supplyAsync(() -> toPlace(place, googleInfo(place)), pool);
	}

	private Optional<GoogleInfo> googleInfo(KakaoPlace place) {
		Cached cached = cache.get(place.id());
		if (cached != null && cached.at().plus(CACHE_TTL).isAfter(Instant.now())) return cached.info();
		try {
			Optional<GoogleInfo> info = google.find(place.name() + " " + place.address(), place.lat(), place.lng())
					.filter(found -> meters(place.lat(), place.lng(), found.lat(), found.lng()) <= MATCH_METERS);
			cache.put(place.id(), new Cached(info, Instant.now()));
			return info;
		} catch (RuntimeException e) {
			// not cached, so the next search asks Google again
			log.warn("Google lookup failed for {}: {}", place.name(), e.getMessage());
			return Optional.empty();
		}
	}

	private static Place toPlace(KakaoPlace place, Optional<GoogleInfo> info) {
		return new Place(place.id(), place.name(), place.address(), place.distanceMeters(), place.lat(), place.lng(), place.url(),
				info.map(GoogleInfo::rating).orElse(null),
				info.map(GoogleInfo::reviewCount).orElse(null),
				info.map(GoogleInfo::priceLevel).orElse(null),
				info.map(GoogleInfo::reviews).orElse(List.of()),
				info.map(GoogleInfo::url).orElse(null));
	}

	private DateCourse dateCourse(List<Place> famous) {
		if (famous.isEmpty()) return null;
		Place restaurant = famous.get(0);
		return new DateCourse(restaurant,
				spot(KakaoClient.CAFE, restaurant, CAFE_RADIUS),
				spot(KakaoClient.SIGHT, restaurant, SIGHT_RADIUS));
	}

	private Spot spot(String category, Place from, int radius) {
		return kakao.nearest(category, from.lat(), from.lng(), radius)
				.map(found -> new Spot(found.name(), found.category(), found.address(), found.distanceMeters(), found.url()))
				.orElse(null);
	}

	// haversine distance
	static int meters(double lat1, double lng1, double lat2, double lng2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLng = Math.toRadians(lng2 - lng1);
		double a = Math.pow(Math.sin(dLat / 2), 2)
				+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
		return (int) Math.round(2 * 6_371_000 * Math.asin(Math.sqrt(a)));
	}

	private record Cached(Optional<GoogleInfo> info, Instant at) {
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.place.PlaceServiceTest'`
Expected: PASS — 9 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pickyourfood/place/PlaceService.java src/test/java/com/example/pickyourfood/place/PlaceServiceTest.java
git commit -m "feat: pick nearby and famous places with Google details and a date course

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Places endpoint

**Files:**
- Create: `src/main/java/com/example/pickyourfood/place/PlaceController.java`
- Test: `src/test/java/com/example/pickyourfood/place/PlaceControllerTest.java`

**Interfaces:**
- Consumes: `PlaceService.locate`, `PlaceService.search`, `PlacesResponse` (Tasks 2–3).
- Produces: `GET /api/places?food=&lat=&lng=` or `GET /api/places?food=&near=` → `PlacesResponse` JSON. Coordinates give `origin.name = "현재 위치"`; `near` wins when both are given.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/pickyourfood/place/PlaceControllerTest.java`:

```java
package com.example.pickyourfood.place;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.pickyourfood.place.PlacesResponse.Origin;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
class PlaceControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	PlaceService places;

	private static PlacesResponse empty(Origin origin) {
		return new PlacesResponse(origin, List.of(), List.of(), null);
	}

	@Test
	void coordinatesSearchFromTheCurrentLocation() throws Exception {
		Origin here = new Origin("현재 위치", 37.54, 127.05);
		when(places.search("탄탄멘", here)).thenReturn(empty(here));

		mvc.perform(get("/api/places").param("food", "탄탄멘").param("lat", "37.54").param("lng", "127.05"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.origin.name").value("현재 위치"))
				.andExpect(jsonPath("$.nearby").isArray());
		verify(places).search("탄탄멘", here);
	}

	@Test
	void nearIsResolvedToAnOrigin() throws Exception {
		Origin seongsu = new Origin("성수동", 37.5445, 127.0557);
		when(places.locate("성수동")).thenReturn(seongsu);
		when(places.search("탄탄멘", seongsu)).thenReturn(empty(seongsu));

		mvc.perform(get("/api/places").param("food", "탄탄멘").param("near", " 성수동 "))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.origin.name").value("성수동"));
	}

	@Test
	void missingFoodOrLocationIsBadRequest() throws Exception {
		mvc.perform(get("/api/places").param("near", "성수동")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/places").param("food", " ").param("near", "성수동")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/places").param("food", "탄탄멘")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/places").param("food", "탄탄멘").param("lat", "37.54")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/places").param("food", "탄탄멘").param("near", " ")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/places").param("food", "탄탄멘").param("lat", "north").param("lng", "127.05"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(places);
	}

	@Test
	void unknownPlaceIsNotFound() throws Exception {
		when(places.locate("없는동네")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

		mvc.perform(get("/api/places").param("food", "탄탄멘").param("near", "없는동네")).andExpect(status().isNotFound());
	}

	@Test
	void kakaoFailureIsBadGateway() throws Exception {
		when(places.search(any(), any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));

		mvc.perform(get("/api/places").param("food", "탄탄멘").param("lat", "37.54").param("lng", "127.05"))
				.andExpect(status().isBadGateway());
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.place.PlaceControllerTest'`
Expected: FAIL — `coordinatesSearchFromTheCurrentLocation` and `nearIsResolvedToAnOrigin` get 404 (no `/api/places` mapping yet); some 400 assertions also fail with 404.

- [ ] **Step 3: Write minimal implementation**

`src/main/java/com/example/pickyourfood/place/PlaceController.java`:

```java
package com.example.pickyourfood.place;

import com.example.pickyourfood.place.PlacesResponse.Origin;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class PlaceController {

	private final PlaceService places;

	PlaceController(PlaceService places) {
		this.places = places;
	}

	@GetMapping("/api/places")
	PlacesResponse places(
			@RequestParam String food,
			@RequestParam(required = false) Double lat,
			@RequestParam(required = false) Double lng,
			@RequestParam(required = false) String near) {
		if (food.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "food is required");
		Origin origin;
		if (near != null && !near.isBlank()) origin = places.locate(near.strip());
		else if (lat != null && lng != null) origin = new Origin("현재 위치", lat, lng);
		else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lat and lng, or near, is required");
		return places.search(food.strip(), origin);
	}
}
```

- [ ] **Step 4: Run test to verify it passes, then the whole suite**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.place.PlaceControllerTest' && ./gradlew test`
Expected: PASS — 5 controller tests; whole suite BUILD SUCCESSFUL with 41 tests (14 + 7 + 6 + 9 + 5), 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pickyourfood/place/PlaceController.java src/test/java/com/example/pickyourfood/place/PlaceControllerTest.java
git commit -m "feat: expose places endpoint

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Places UI building blocks

**Files:**
- Modify: `frontend/src/shared/api.ts`
- Create: `frontend/src/features/places/api.ts`
- Create: `frontend/src/features/places/format.ts`
- Create: `frontend/src/features/places/MapLink.tsx`
- Create: `frontend/src/features/places/PlaceTable.tsx`
- Create: `frontend/src/features/places/DateCourse.tsx`

**Interfaces:**
- Consumes: `GET /api/places` JSON (Task 4), `request` from `shared/api.ts`.
- Produces:
  - `shared/api.ts`: `class HttpError extends Error { status: number }`; `request()` now throws `HttpError`
  - `features/places/api.ts`: types `Review`, `Place`, `Spot`, `DateCourse`, `Places`, `Where = { lat: number; lng: number } | { near: string }`; `fetchPlaces(food: string, where: Where): Promise<Places>`
  - `PlaceTable` props `{ food: string; nearby: Place[]; famous: Place[] }`
  - `DateCourse` props `{ course: DateCourse }`

There is no frontend test framework (spec §6); the gate for this task is the type-checked build.

- [ ] **Step 1: Make request errors carry the status**

Replace `frontend/src/shared/api.ts` with:

```ts
export type Food = {
  id: string
  name: string
  description: string
}

export class HttpError extends Error {
  status: number

  constructor(status: number) {
    super(`HTTP ${status}`)
    this.status = status
  }
}

export async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init)
  if (!res.ok) throw new HttpError(res.status)
  return res.json() as Promise<T>
}
```

- [ ] **Step 2: Add the places API types and call**

`frontend/src/features/places/api.ts`:

```ts
import { request } from '../../shared/api.ts'

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
  name: string
  category: string
  address: string
  distanceMeters: number
  kakaoUrl: string
}

export type DateCourse = {
  restaurant: Place
  cafe: Spot | null
  sight: Spot | null
}

export type Places = {
  origin: { name: string; lat: number; lng: number }
  nearby: Place[]
  famous: Place[]
  dateCourse: DateCourse | null
}

export type Where = { lat: number; lng: number } | { near: string }

export function fetchPlaces(food: string, where: Where): Promise<Places> {
  const params = new URLSearchParams(
    'near' in where ? { food, near: where.near } : { food, lat: String(where.lat), lng: String(where.lng) },
  )
  return request(`/api/places?${params}`)
}
```

- [ ] **Step 3: Add formatting helpers and the map link**

`frontend/src/features/places/format.ts`:

```ts
import type { Place } from './api.ts'

export function distance(meters: number): string {
  return meters < 1000 ? `${meters}m` : `${(meters / 1000).toFixed(1)}km`
}

// one line for narrow screens, e.g. "₩₩ · ★4.3 · 리뷰 1,284 · 820m"; unknown parts are left out
export function summary(place: Place): string {
  return [
    place.priceLevel !== null && '₩'.repeat(place.priceLevel),
    place.rating !== null && `★${place.rating.toFixed(1)}`,
    place.reviewCount !== null && `리뷰 ${place.reviewCount.toLocaleString('ko-KR')}`,
    distance(place.distanceMeters),
  ]
    .filter(Boolean)
    .join(' · ')
}
```

`frontend/src/features/places/MapLink.tsx`:

```tsx
import { ArrowUpRight } from '@phosphor-icons/react'

export default function MapLink({ href, label }: { href: string; label: string }) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noreferrer"
      className="flex w-fit items-center gap-1 rounded-full border border-zinc-300 px-4 py-2 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]"
    >
      {label} <ArrowUpRight size={14} />
    </a>
  )
}
```

- [ ] **Step 4: Add the comparison table**

`frontend/src/features/places/PlaceTable.tsx`:

```tsx
import { ArrowDown, ArrowUp } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useState } from 'react'
import type { Place } from './api.ts'
import { distance, summary } from './format.ts'
import MapLink from './MapLink.tsx'

type SortKey = 'distance' | 'price' | 'rating' | 'reviews'
type Sort = { key: SortKey; asc: boolean }

const TABS = [
  { key: 'nearby', label: '주위 1km', radius: '1km', sort: { key: 'distance', asc: true } },
  { key: 'famous', label: '근교 유명 맛집', radius: '20km', sort: { key: 'reviews', asc: false } },
] as const satisfies readonly { key: string; label: string; radius: string; sort: Sort }[]

type Tab = (typeof TABS)[number]

const COLUMNS: { key: SortKey; label: string }[] = [
  { key: 'distance', label: '이름·거리' },
  { key: 'price', label: '가격대' },
  { key: 'rating', label: '평점' },
  { key: 'reviews', label: '리뷰 수' },
]

const GRID = 'md:grid-cols-[1fr_6rem_5rem_6rem]'

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

function value(place: Place, key: SortKey): number | null {
  switch (key) {
    case 'distance':
      return place.distanceMeters
    case 'price':
      return place.priceLevel
    case 'rating':
      return place.rating
    case 'reviews':
      return place.reviewCount
  }
}

// unknown values stay last whichever way the column is sorted
function sortPlaces(places: Place[], sort: Sort): Place[] {
  return [...places].sort((a, b) => {
    const x = value(a, sort.key)
    const y = value(b, sort.key)
    if (x === null || y === null) return x === y ? 0 : x === null ? 1 : -1
    return sort.asc ? x - y : y - x
  })
}

type Props = {
  food: string
  nearby: Place[]
  famous: Place[]
}

export default function PlaceTable({ food, nearby, famous }: Props) {
  const [tab, setTab] = useState<Tab>(TABS[0])
  const [sort, setSort] = useState<Sort>(TABS[0].sort)
  const [open, setOpen] = useState<string | null>(null)
  const places = sortPlaces(tab.key === 'nearby' ? nearby : famous, sort)

  function switchTab(next: Tab) {
    setTab(next)
    setSort(next.sort)
    setOpen(null)
  }

  function sortBy(key: SortKey) {
    // a new column starts with its natural direction: closest and cheapest first, best-rated and most-reviewed first
    setSort((prev) => (prev.key === key ? { key, asc: !prev.asc } : { key, asc: key === 'distance' || key === 'price' }))
  }

  return (
    <div>
      <div className="flex w-fit gap-1 rounded-full border border-zinc-200 bg-white p-1">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            onClick={() => switchTab(t)}
            className="relative rounded-full px-4 py-2 text-sm font-medium transition active:scale-[0.98]"
          >
            {tab.key === t.key && (
              <motion.span layoutId="places-tab" className="absolute inset-0 rounded-full bg-zinc-900" transition={spring} />
            )}
            <span className={`relative ${tab.key === t.key ? 'text-white' : 'text-zinc-600'}`}>{t.label}</span>
          </button>
        ))}
      </div>

      {places.length === 0 ? (
        <p className="mt-8 max-w-[50ch] text-base leading-relaxed text-zinc-500">
          {tab.radius} 안에서 {food} 파는 곳을 못 찾았어요.{' '}
          {tab.key === 'nearby' ? '근교 탭을 보거나 다른 동네로 찾아보세요.' : '다른 동네로 찾아보세요.'}
        </p>
      ) : (
        <div className="mt-6">
          <div className={`hidden border-b border-zinc-200 pb-3 text-sm text-zinc-500 md:grid ${GRID}`}>
            {COLUMNS.map((column, index) => (
              <button
                key={column.key}
                type="button"
                onClick={() => sortBy(column.key)}
                className={`flex items-center gap-1 transition hover:text-zinc-900 ${index === 0 ? '' : 'justify-end'} ${
                  sort.key === column.key ? 'text-zinc-900' : ''
                }`}
              >
                {column.label}
                {sort.key === column.key && (sort.asc ? <ArrowUp size={12} /> : <ArrowDown size={12} />)}
              </button>
            ))}
          </div>
          <ul className="divide-y divide-zinc-200 border-b border-zinc-200">
            {places.map((place) => (
              <li key={place.id}>
                <PlaceRow
                  place={place}
                  open={open === place.id}
                  onToggle={() => setOpen(open === place.id ? null : place.id)}
                />
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

type PlaceRowProps = {
  place: Place
  open: boolean
  onToggle: () => void
}

function PlaceRow({ place, open, onToggle }: PlaceRowProps) {
  return (
    <>
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className={`grid w-full gap-1 py-4 text-left transition hover:bg-zinc-100/60 md:items-center ${GRID}`}
      >
        <span>
          <span className="block text-lg font-semibold tracking-tight">{place.name}</span>
          <span className="hidden font-mono text-sm text-zinc-500 md:block">{distance(place.distanceMeters)}</span>
        </span>
        <span className="font-mono text-sm text-zinc-500 md:hidden">{summary(place)}</span>
        <span className="hidden text-right md:block">
          <Price level={place.priceLevel} />
        </span>
        <span className="hidden text-right font-mono md:block">{place.rating?.toFixed(1) ?? <Unknown />}</span>
        <span className="hidden text-right font-mono md:block">
          {place.reviewCount?.toLocaleString('ko-KR') ?? <Unknown />}
        </span>
      </button>
      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -8 }}
            transition={spring}
            className="pb-6"
          >
            <Reviews place={place} />
          </motion.div>
        )}
      </AnimatePresence>
    </>
  )
}

function Reviews({ place }: { place: Place }) {
  return (
    <div className="grid gap-6 rounded-2xl bg-white p-5 md:grid-cols-[1fr_auto] md:gap-10">
      {place.reviews.length === 0 ? (
        <p className="text-sm text-zinc-500">리뷰 정보가 없어요.</p>
      ) : (
        <ul className="grid gap-4">
          {place.reviews.map((review, index) => (
            <li key={index}>
              <p className="line-clamp-3 text-sm leading-relaxed text-zinc-700">{review.text}</p>
              <p className="mt-1 font-mono text-xs text-zinc-400">
                {[review.rating !== null && `★${review.rating}`, review.author ?? '익명', review.when]
                  .filter(Boolean)
                  .join(' · ')}
              </p>
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap gap-2 md:flex-col">
        <MapLink href={place.kakaoUrl} label="카카오맵" />
        {place.googleUrl && <MapLink href={place.googleUrl} label="Google 지도" />}
      </div>
    </div>
  )
}

function Unknown() {
  return <span className="text-zinc-300">—</span>
}

function Price({ level }: { level: number | null }) {
  if (level === null) return <Unknown />
  return (
    <span className="font-mono">
      {'₩'.repeat(level)}
      <span className="text-zinc-300">{'₩'.repeat(4 - level)}</span>
    </span>
  )
}
```

- [ ] **Step 5: Add the date course timeline**

`frontend/src/features/places/DateCourse.tsx`:

```tsx
import { Coffee, ForkKnife, Mountains } from '@phosphor-icons/react'
import type { Icon } from '@phosphor-icons/react'
import { motion } from 'motion/react'
import type { DateCourse as Course } from './api.ts'
import { distance } from './format.ts'
import MapLink from './MapLink.tsx'

type Step =
  | { icon: Icon; label: string; name: string; detail: string; url: string }
  | { icon: Icon; label: string; missing: string }

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

export default function DateCourse({ course }: { course: Course }) {
  const { restaurant, cafe, sight } = course
  const steps: Step[] = [
    { icon: ForkKnife, label: '식사', name: restaurant.name, detail: restaurant.address, url: restaurant.kakaoUrl },
    cafe
      ? { icon: Coffee, label: '카페', name: cafe.name, detail: `${cafe.category} · 식당에서 ${distance(cafe.distanceMeters)}`, url: cafe.kakaoUrl }
      : { icon: Coffee, label: '카페', missing: '근처에 카페를 찾지 못했어요' },
    sight
      ? { icon: Mountains, label: '볼거리', name: sight.name, detail: `${sight.category} · 식당에서 ${distance(sight.distanceMeters)}`, url: sight.kakaoUrl }
      : { icon: Mountains, label: '볼거리', missing: '근처에 볼거리를 찾지 못했어요' },
  ]

  return (
    <div>
      <p className="text-sm font-medium text-accent">데이트 코스</p>
      <h3 className="mt-2 text-2xl font-semibold tracking-tight">{restaurant.name}에서 시작해요</h3>
      <ol className="mt-8 grid gap-8 md:grid-cols-3 md:gap-6">
        {steps.map((step, index) => (
          <motion.li
            key={step.label}
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ ...spring, delay: index * 0.15 }}
            className="border-l border-zinc-300 pl-6 md:border-l-0 md:border-t md:pl-0 md:pt-6"
          >
            <p className="flex items-center gap-2 text-sm text-zinc-500">
              <span className="font-mono text-accent">{String(index + 1).padStart(2, '0')}</span>
              <step.icon size={16} /> {step.label}
            </p>
            {'missing' in step ? (
              <p className="mt-3 text-base text-zinc-400">{step.missing}</p>
            ) : (
              <>
                <p className="mt-3 text-xl font-semibold tracking-tight">{step.name}</p>
                <p className="mt-1 text-sm text-zinc-500">{step.detail}</p>
                <div className="mt-4">
                  <MapLink href={step.url} label="카카오맵" />
                </div>
              </>
            )}
          </motion.li>
        ))}
      </ol>
    </div>
  )
}
```

- [ ] **Step 6: Type-check and build**

Run: `cd frontend && npm run build`
Expected: `✓ built in …` with no TypeScript errors. (The new components are not rendered yet; `tsc -b` still checks them because `tsconfig.app.json` includes all of `src`.)

- [ ] **Step 7: Commit**

```bash
git add frontend/src/shared/api.ts frontend/src/features/places
git commit -m "feat: add places comparison table and date course components

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Places section on the result screen

**Files:**
- Create: `frontend/src/features/places/PlacesSection.tsx`
- Modify: `frontend/src/App.tsx` (render `PlacesSection` under `ResultView`)
- Scratch (not committed): `<workspace>/places-check.mjs` — the plan workspace printed by `sdd-workspace`

**Interfaces:**
- Consumes: `fetchPlaces`, `Where`, `Places`, `PlaceTable`, `DateCourse` (Task 5); `HttpError` (Task 5).
- Produces: `PlacesSection` props `{ food: string }`.

- [ ] **Step 1: Add the section**

`frontend/src/features/places/PlacesSection.tsx`:

```tsx
import { Crosshair, MapPin } from '@phosphor-icons/react'
import { useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { HttpError } from '../../shared/api.ts'
import { fetchPlaces } from './api.ts'
import type { Places, Where } from './api.ts'
import DateCourse from './DateCourse.tsx'
import PlaceTable from './PlaceTable.tsx'

type State = { status: 'idle' | 'locating' | 'loading' | 'error' } | { status: 'done'; data: Places }

export default function PlacesSection({ food }: { food: string }) {
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
    fetchPlaces(food, where).then(
      (data) => id === latest.current && setState({ status: 'done', data }),
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
            {state.data.dateCourse && (
              <div className="mt-16">
                <DateCourse course={state.data.dateCourse} />
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

- [ ] **Step 2: Render it under the result**

In `frontend/src/App.tsx`, add the import after the `ModeSelect` import:

```tsx
import PlacesSection from './features/places/PlacesSection.tsx'
```

and replace the result branch

```tsx
          {(screen === 'random' || screen === 'recommend') && (
            <ResultView
```

through its closing `/>` and `)}` with:

```tsx
          {(screen === 'random' || screen === 'recommend') && (
            <>
              <ResultView
                title={screen === 'random' ? '오늘의 랜덤 메뉴' : '당신에게 딱 맞는 메뉴'}
                status={result.status}
                best={result.status === 'done' ? result.best : undefined}
                alternatives={result.status === 'done' ? result.alternatives : []}
                roll={screen === 'random'}
                againLabel={screen === 'random' ? '다시 뽑기' : '다시 하기'}
                onAgain={screen === 'random' ? drawRandom : () => setScreen('survey')}
                onRetry={() => lastRequest.current()}
                onHome={goHome}
              />
              {result.status === 'done' && <PlacesSection food={result.best.name} />}
            </>
          )}
```

- [ ] **Step 3: Type-check and build**

Run: `cd frontend && npm run build`
Expected: `✓ built in …`, no TypeScript errors.

- [ ] **Step 4: Write the browser check script**

`<workspace>/places-check.mjs` (answers every `/api/*` call from fixtures, so no backend or keys are needed):

```js
// usage: node places-check.mjs <happy|denied|notfound|error|empty|mobile>   (Vite dev server on :5173)
import { spawn } from 'node:child_process'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scenario = process.argv[2] ?? 'happy'
const PORT = 9334
const chrome = spawn(
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${mkdtempSync(join(tmpdir(), 'places-'))}`, '--window-size=1280,900', 'about:blank'],
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
const type = (value) =>
  js(`(() => { const el = document.getElementById('near'); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, ${JSON.stringify(value)}); el.dispatchEvent(new Event('input', { bubbles: true })) })()`)
const order = () => js(`[...document.querySelectorAll('button[aria-expanded]')].map((b) => b.querySelector('span span').innerText).join(',')`)
const check = (label, ok) => {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}`)
  if (!ok) process.exitCode = 1
}
const screenshot = async (name) =>
  writeFileSync(join(import.meta.dirname, `${name}.png`), Buffer.from((await send('Page.captureScreenshot', { captureBeyondViewport: true })).data, 'base64'))

const place = (id, name, meters, rating, reviewCount, priceLevel, reviews = []) => ({
  id, name, address: `서울 성동구 ${name}길 1`, distanceMeters: meters, lat: 37.54, lng: 127.05,
  kakaoUrl: `https://place.map.kakao.com/${id}`, rating, reviewCount, priceLevel, reviews,
  googleUrl: rating === null ? null : `https://maps.google.com/?cid=${id}`,
})
const REVIEWS = [
  { author: '김서윤', rating: 5, text: '국물이 진하고 면이 쫄깃해요.', when: '2주 전' },
  { author: null, rating: 4, text: '웨이팅이 길지만 먹을 만해요.', when: '1달 전' },
]
const FULL = {
  origin: { name: '현재 위치', lat: 37.54, lng: 127.05 },
  nearby: [place('n1', '탄탄공방', 180, 4.1, 212, 2, REVIEWS), place('n2', '마라부엌', 420, null, null, null), place('n3', '면사랑', 760, 4.6, 58, 1)],
  famous: [place('f1', '성수탄탄', 4200, 4.4, 1284, 2, REVIEWS), place('f2', '담담', 8800, 4.7, 512, 3), place('f3', '옛날탄탄', 15300, null, null, null)],
  dateCourse: {
    restaurant: place('f1', '성수탄탄', 4200, 4.4, 1284, 2, REVIEWS),
    cafe: { name: '어니언 성수', category: '커피전문점', address: '서울 성동구 아차산로9길 8', distanceMeters: 320, kakaoUrl: 'https://place.map.kakao.com/c1' },
    sight: null,
  },
}
const EMPTY = { origin: { name: '현재 위치', lat: 37.54, lng: 127.05 }, nearby: [], famous: [], dateCourse: null }
const FOOD = { id: 'tantanmen', name: '탄탄멘', description: '고소한 참깨 국물에 매콤한 고추기름.' }

let placesCalls = 0
function answer(url) {
  if (url.includes('/api/foods/random')) return [200, FOOD]
  placesCalls++
  if (scenario === 'notfound' && url.includes('near=')) return [404, {}]
  if (scenario === 'error' && placesCalls === 1) return [502, {}]
  if (scenario === 'empty') return [200, EMPTY]
  if (url.includes('near=')) return [200, { ...FULL, origin: { ...FULL.origin, name: '성수동' } }]
  return [200, FULL]
}
listeners.push(async (msg) => {
  if (msg.method !== 'Fetch.requestPaused') return
  const [status, body] = answer(msg.params.request.url)
  await send('Fetch.fulfillRequest', {
    requestId: msg.params.requestId,
    responseCode: status,
    responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
    body: Buffer.from(JSON.stringify(body)).toString('base64'),
  })
})

const geolocation =
  scenario === 'denied'
    ? `(ok, fail) => setTimeout(() => fail({ code: 1, message: 'denied' }), 50)`
    : `(ok) => setTimeout(() => ok({ coords: { latitude: 37.54, longitude: 127.05, accuracy: 10 } }), 50)`
await send('Page.enable')
await send('Runtime.enable')
await send('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }] })
await send('Page.addScriptToEvaluateOnNewDocument', {
  source: `Object.defineProperty(navigator, 'geolocation', { value: { getCurrentPosition: ${geolocation} } })`,
})
if (scenario === 'mobile') await send('Emulation.setDeviceMetricsOverride', { width: 375, height: 812, deviceScaleFactor: 2, mobile: true })

try {
  await send('Page.navigate', { url: 'http://localhost:5173/' })
  await waitFor('오늘 뭐 먹지?')
  await click('랜덤으로 뽑기')
  await waitFor('이 메뉴, 어디서 먹지?')

  if (scenario === 'happy') {
    await click('현재 위치로')
    await waitFor('현재 위치 기준')
    check('nearby starts sorted by distance', (await order()) === '탄탄공방,마라부엌,면사랑')
    await click('평점')
    check('rating high first, unknown last', (await order()) === '면사랑,탄탄공방,마라부엌')
    await click('평점')
    check('rating low first, unknown still last', (await order()) === '탄탄공방,면사랑,마라부엌')
    await click('근교 유명 맛집')
    await sleep(300)
    check('famous starts by review count', (await order()) === '성수탄탄,담담,옛날탄탄')
    await click('성수탄탄')
    await waitFor('국물이 진하고')
    const body = await text()
    check('expanded row has map links', body.includes('카카오맵') && body.includes('Google 지도'))
    check('anonymous review author', body.includes('익명'))
    check('course shows cafe with distance', body.includes('어니언 성수') && body.includes('식당에서 320m'))
    check('course marks missing sight', body.includes('근처에 볼거리를 찾지 못했어요'))
    check('A result still shown', body.includes('다시 뽑기'))
    await screenshot('places-happy')
  } else if (scenario === 'denied') {
    await click('현재 위치로')
    await waitFor('위치 권한이 없어요. 동네 이름으로 찾아보세요.')
    check('focus moved to the input', (await js('document.activeElement.id')) === 'near')
    await type('성수동')
    await click('찾기')
    await waitFor('성수동 기준')
    check('typed place works after denial', true)
  } else if (scenario === 'notfound') {
    await type('없는동네')
    await click('찾기')
    await waitFor("'없는동네'을 찾지 못했어요.")
    check('not-found notice under the input', true)
  } else if (scenario === 'error') {
    await click('현재 위치로')
    await waitFor('주변 맛집을 불러오지 못했어요.')
    check('A result untouched by places error', (await text()).includes('다시 뽑기'))
    await click('다시 시도')
    await waitFor('현재 위치 기준')
    check('retry recovers', true)
  } else if (scenario === 'empty') {
    await click('현재 위치로')
    await waitFor('1km 안에서 탄탄멘 파는 곳을 못 찾았어요.')
    check('no course when there is none', !(await text()).includes('데이트 코스'))
    await click('근교 유명 맛집')
    await waitFor('20km 안에서 탄탄멘 파는 곳을 못 찾았어요.')
    check('famous tab empty message', true)
  } else if (scenario === 'mobile') {
    await click('현재 위치로')
    await waitFor('현재 위치 기준')
    check('no horizontal scroll at 375px', (await js('document.documentElement.scrollWidth')) <= 375)
    check('one-line summary on mobile', (await text()).includes('₩₩ · ★4.1 · 리뷰 212 · 180m'))
    await screenshot('places-mobile')
  }
} catch (error) {
  console.log(`FAIL ${error.message}`)
  process.exitCode = 1
} finally {
  ws.close()
  chrome.kill()
}
```

- [ ] **Step 5: Run every browser scenario**

Run (start Vite first, from the repo root; stop it afterwards):

```bash
(cd frontend && npm run dev -- --port 5173 --strictPort > /dev/null 2>&1 &) ; sleep 3
for s in happy denied notfound error empty mobile; do echo "== $s"; node <workspace>/places-check.mjs $s; done
```

Expected: every line starts with `PASS` (no `FAIL`) across all six scenarios. Then open `places-happy.png` and `places-mobile.png` from the workspace and look at them: table columns aligned, tab pill on the active tab, course timeline horizontal on desktop and vertical on mobile, no emoji.

- [ ] **Step 6: Live check with real keys (only if both keys are set)**

Run: `[ -n "$KAKAO_REST_KEY" ] && [ -n "$GOOGLE_PLACES_KEY" ] && echo keys || echo no-keys`

- `no-keys`: record `Task 6: live API check skipped — KAKAO_REST_KEY/GOOGLE_PLACES_KEY not set` in the ledger and continue.
- `keys`: start the backend (`export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew bootRun` in the background), then
  `curl -s 'http://localhost:8080/api/places?food=%ED%83%84%ED%83%84%EB%A9%98&near=%EC%84%B1%EC%88%98%EB%8F%99' | head -c 600`
  Expected: JSON with `"origin":{"name":"성수동"` and non-empty `nearby` or `famous`. Stop the backend afterwards.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/places/PlacesSection.tsx frontend/src/App.tsx
git commit -m "feat: show places section under result screens

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
