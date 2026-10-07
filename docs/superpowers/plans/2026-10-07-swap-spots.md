# H1. 코스의 카페·볼거리 바꾸기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 검색 화면의 모든 코스에서 카페·볼거리를 가까운 후보 최대 5곳 중 하나로 바꾸고, 구간·지도·전체 경로를 새 장소로 다시 계산해 저장까지 남긴다.

**Architecture:** 서버 `place`는 카카오 근처 검색을 5곳으로 늘려 코스 응답에 후보 `cafes`·`sights`(식당에서 거리 포함)를 함께 보낸다. 구간 계산을 `walkAsync(List<Stop>)`로 떼어 코스와 새 `GET /api/places/legs`가 함께 쓰고, 경로 캐시 키에 도착지 좌표를 더한다. 화면은 `shared/places/DateCourses`에 후보 목록을 그리고, `features/places/PlacesSection`이 고른 장소로 구간을 요청해 응답이 오면 장소와 구간을 함께 바꾼다. 저장할 때 `features/saved/api.ts`가 후보와 거리를 뺀다.

**Tech Stack:** Spring Boot 4.1.1, Java 17, Jackson 3, MockMvc, Mockito; React + Vite + TypeScript 6, Tailwind v4, Phosphor icons, motion/react, oxlint.

**Spec:** `docs/superpowers/specs/2026-10-07-swap-spots-design.md`

## Global Constraints

- Gradle 명령은 모두 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` 뒤에 실행한다.
- package-by-feature. 백엔드는 `place`와 `account/SecurityConfig`만, 프론트엔드는 `features/places/`·`features/saved/api.ts`·`shared/places/`만 바뀐다. 기능끼리 import하지 않는다. 저장 형식(`saved` 백엔드)은 바꾸지 않는다.
- 새 의존성 없음. 아이콘은 `@phosphor-icons/react`만, 이모지 금지. 강조색은 Tailwind `accent`.
- 문구(그대로): `다른 곳`, `지금`, `경로 다시 계산 중…`, `바꾸지 못했어요. 다시 시도해 주세요.`
- 후보는 카카오 근처 검색 `size` 5. 코스 응답 `cafes`·`sights`는 가까운 순, 검색 실패 시 `[]`.
- `/api/places/legs` 입력 검사: 네 목록(`id`·`name`·`lat`·`lng`) 길이가 같고 2~3개, 각 `id`·`name`은 공백 제거 후 비어 있지 않고 100자 이하이며 제어 문자 없음, `lat` -90~90, `lng` -180~180(NaN 거부). 위반은 400. 로그인 불필요.
- 경로 캐시 키: `from.id@from.lat,from.lng>to.id@to.lat,to.lng`.
- 바꾸기는 구간 응답이 온 뒤에 장소와 구간을 함께 반영한다. 바꾸기와 내 코스 만들기는 카운터 `latestCourse` 하나를 함께 써서 나중에 누른 것만 반영한다.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- `.claude/`, `.serena/`, `graphify-out/`, `data/`, `.superpowers/`는 절대 `git add`하지 않는다.

## Review Focus

1. **진짜 카페 id에 가짜 좌표를 붙인 `/legs` 요청** — 그 카페로 가는 진짜 코스의 캐시 경로가 오염되면 안 된다. 테스트: Task 2 `walkWithAFakeCafeLocationDoesNotPoisonTheRouteCacheForTheRealCafe`.
2. **이름에 쉼표가 있는 장소**(`어니언, 성수`) — 반복 파라미터가 쉼표에서 쪼개져 목록 길이가 어긋나거나 이름이 바뀌면 안 된다. 테스트: Task 2 `walkNeedsNoLogin`(이름에 쉼표).
3. **바꾸기 요청 중 다른 코스에서 또 바꾸기, 또는 내 코스 만들기** — 먼저 누른 응답이 늦게 와도 화면을 바꾸지 않는다. 장소만 바뀌고 구간은 예전 것으로 남는 코스가 생기면 안 된다. 확인: Task 3 헤드리스 확인 3·8.
4. **후보 목록을 연 채로 다른 코스 탭으로 옮김** — 목록은 닫혀야 한다. 다른 코스의 후보가 열린 채 남으면 안 된다. 확인: Task 3 헤드리스 확인 5.
5. **저장 요청 크기** — 후보 목록을 실은 채 저장하면 64KB 상한에 가까워진다. 저장 본문에 `cafes`·`sights`·`distanceMeters`가 없어야 한다. 확인: Task 3 헤드리스 확인 4.

---

### Task 1: 코스 응답에 후보와 거리 싣기

**Files:**
- Modify: `src/main/java/com/example/pickyourfood/place/KakaoClient.java:50-55`
- Modify: `src/main/java/com/example/pickyourfood/place/PlacesResponse.java`
- Modify: `src/main/java/com/example/pickyourfood/place/PlaceService.java:132-200`
- Test: `src/test/java/com/example/pickyourfood/place/KakaoClientTest.java:70-90`
- Test: `src/test/java/com/example/pickyourfood/place/PlaceServiceTest.java`
- Test: `src/test/java/com/example/pickyourfood/place/PlaceControllerTest.java:95-125`

**Interfaces:**
- Consumes: 기존 `PlaceService`의 `candidatesAsync`, `pick`, `courseAsync`, `Stop`, `KakaoClient.KakaoPlace(id, name, category, address, lat, lng, distanceMeters, url)`.
- Produces:
  - `PlacesResponse.Spot(String id, String name, String category, String address, double lat, double lng, String kakaoUrl, int distanceMeters)`.
  - `PlacesResponse.DateCourse(Place restaurant, Spot cafe, Spot sight, List<Leg> legs, String routeUrl, List<Spot> cafes, List<Spot> sights)`.
  - `PlacesResponse.Course(Spot cafe, Spot sight, List<Leg> legs, String routeUrl, List<Spot> cafes, List<Spot> sights)`.
  - JSON: 코스마다 `"cafes": [Spot]`, `"sights": [Spot]`, 각 Spot에 `"distanceMeters": number`.

- [ ] **Step 1: Write the failing tests**

`KakaoClientTest.java`의 `nearbySendsCategorySearchAndReturnsTheNearestThree`를 `nearbySendsCategorySearchAndReturnsTheNearestFive`로 이름을 바꾸고, 그 안의 `.andExpect(queryParam("size", "3"))`를 `.andExpect(queryParam("size", "5"))`로 바꾼다.

`PlaceServiceTest.java`에 import `com.example.pickyourfood.place.PlacesResponse.Spot;`를 더하고, `everyCandidateTakenFallsBackToTheNearest` 테스트 바로 아래에 넣는다:

```java
	@Test
	void coursesOfferEveryNearbyCandidateNearestFirst() {
		famousRanked("r1", "r2");
		cafesNear(37.56, spot("shared", 37.561), spot("second", 37.562));
		cafesNear(37.57, spot("shared", 37.561), spot("own", 37.572));

		List<DateCourse> courses = service.search("ramen", ORIGIN).dateCourses();

		assertThat(courses.get(0).cafes()).extracting(Spot::id).containsExactly("shared", "second");
		assertThat(courses.get(1).cafe().id()).isEqualTo("own");
		// a spot an earlier course uses is still offered
		assertThat(courses.get(1).cafes()).extracting(Spot::id).containsExactly("shared", "own");
		assertThat(courses.get(0).sights()).isEmpty();
	}
```

`pickedRestaurantWithNothingNearbyHasNoLegsOrRoute` 바로 아래에 넣는다:

```java
	@Test
	void pickedCourseOffersTheCandidatesWithTheirDistance() {
		cafesNear(37.55, new KakaoPlace("near", "near", "카페", "주소 near", 37.551, 127.05, 120, "https://place.map.kakao.com/near"),
				spot("far", 37.556));

		Course course = service.course("p1", "고른 식당", 37.55, 127.05);

		assertThat(course.cafe().distanceMeters()).isEqualTo(120);
		assertThat(course.cafes()).extracting(Spot::id).containsExactly("near", "far");
		assertThat(course.sights()).isEmpty();
	}
```

`pickedRestaurantWithAFailedCafeSearchWalksToTheSight`의 마지막 assert 아래에 두 줄을 더한다:

```java
		assertThat(course.cafes()).isEmpty();
		assertThat(course.sights()).extracting(Spot::id).containsExactly("sight");
```

`PlaceControllerTest.java`에서 생성자를 새 모양에 맞춘다:
- `legPathIsSentAsLatLngPairs`: `new DateCourse(restaurant, null, null, List.of(leg, straight), "https://map.kakao.com/link/by/walk/a", List.of(), List.of())`.
- `pickedCourseNeedsNoLogin`: `Spot cafe = new Spot("c1", "어니언", "카페", "", 37.554, 127.05, "", 120);`, 반환은 `new Course(cafe, null, List.of(leg), "https://map.kakao.com/link/by/walk/a", List.of(cafe), List.of())`. 그리고 `.andExpect(jsonPath("$.cafe.name").value("어니언"))` 아래에 더한다:

```java
				.andExpect(jsonPath("$.cafes[0].distanceMeters").value(120))
				.andExpect(jsonPath("$.sights").isEmpty())
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests '*place*'`
Expected: 컴파일 실패(`cafes()`·`sights()`·`distanceMeters()` 없음, 생성자 인자 수 다름).

- [ ] **Step 3: Implement**

`KakaoClient.java`: 주석 `// the three nearest places of a category, nearest first`를 `// the five nearest places of a category, nearest first`로, `nearby`의 `.queryParam("size", 3)`을 `.queryParam("size", 5)`로.

`PlacesResponse.java`의 `Spot`, `DateCourse`, `Course`를 바꾼다:

```java
	// distanceMeters is Kakao's distance from the restaurant the spot was searched around
	public record Spot(String id, String name, String category, String address, double lat, double lng, String kakaoUrl, int distanceMeters) {
	}
```

```java
	// cafe and sight are null when nothing is found near the restaurant; legs join only the stops that exist,
	// and routeUrl (a Kakao Map walking route through every stop) is null when the restaurant is the only stop;
	// cafes and sights are the nearest candidates the user can swap to (nearest first, [] when the search failed)
	public record DateCourse(Place restaurant, Spot cafe, Spot sight, List<Leg> legs, String routeUrl, List<Spot> cafes, List<Spot> sights) {
	}

	// a course without its restaurant: what GET /api/places/course answers for a restaurant the user picked,
	// with the same fields and nulls as DateCourse
	public record Course(Spot cafe, Spot sight, List<Leg> legs, String routeUrl, List<Spot> cafes, List<Spot> sights) {
	}
```

`PlaceService.java`:

`dateCourses`의 반복문 안을 바꾼다(고르는 순서는 그대로, `courseAsync`가 안에서 고른다):

```java
		for (int i = 0; i < restaurants.size(); i++) {
			Place restaurant = restaurants.get(i);
			courses.add(courseAsync(stop(restaurant), cafes.get(i).join(), sights.get(i).join(), used)
					.thenApply(found -> new DateCourse(restaurant, found.cafe(), found.sight(), found.legs(), found.routeUrl(),
							found.cafes(), found.sights())));
		}
```

`course`의 마지막 줄:

```java
		return courseAsync(start, cafes.join(), sights.join(), new HashSet<>()).join();
```

`pick`의 `.map`이 새 헬퍼를 쓰게 하고, 헬퍼 둘을 `pick` 바로 아래에 더한다:

```java
				.map(found -> {
					used.add(found.id());
					return spot(found);
				})
```

```java
	private static Spot spot(KakaoPlace found) {
		return new Spot(found.id(), found.name(), found.category(), found.address(), found.lat(), found.lng(), found.url(),
				found.distanceMeters());
	}

	private static List<Spot> spots(List<KakaoPlace> found) {
		return found.stream().map(PlaceService::spot).toList();
	}
```

`courseAsync`가 후보 목록을 받아 고르고, 후보를 응답에 싣게 한다:

```java
	// picks the cafe and sight no earlier course uses (see pick) and offers every candidate for swapping
	private CompletableFuture<Course> courseAsync(Stop start, List<KakaoPlace> cafes, List<KakaoPlace> sights, Set<String> used) {
		Spot cafe = pick(cafes, used);
		Spot sight = pick(sights, used);
		List<Stop> stops = new ArrayList<>();
		stops.add(start);
		if (cafe != null) stops.add(new Stop(cafe.id(), cafe.name(), cafe.lat(), cafe.lng()));
		if (sight != null) stops.add(new Stop(sight.id(), sight.name(), sight.lat(), sight.lng()));
		List<CompletableFuture<Leg>> legs = IntStream.range(1, stops.size())
				.mapToObj(i -> legAsync(stops.get(i - 1), stops.get(i))).toList();
		String routeUrl = legs.isEmpty() ? null
				: ROUTE_BASE + stops.stream()
						.map(stop -> routeName(stop.name()) + "," + stop.lat() + "," + stop.lng())
						.collect(Collectors.joining("/"));
		return CompletableFuture.allOf(legs.toArray(CompletableFuture[]::new))
				.thenApply(done -> new Course(cafe, sight, legs.stream().map(CompletableFuture::join).toList(), routeUrl,
						spots(cafes), spots(sights)));
	}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test`
Expected: BUILD SUCCESSFUL, 실패 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pickyourfood/place/KakaoClient.java src/main/java/com/example/pickyourfood/place/PlacesResponse.java src/main/java/com/example/pickyourfood/place/PlaceService.java src/test/java/com/example/pickyourfood/place/KakaoClientTest.java src/test/java/com/example/pickyourfood/place/PlaceServiceTest.java src/test/java/com/example/pickyourfood/place/PlaceControllerTest.java
git commit -m "feat: offer the nearest cafes and sights with every course

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 고른 장소로 구간 다시 계산하는 API

**Files:**
- Modify: `src/main/java/com/example/pickyourfood/place/PlacesResponse.java`
- Modify: `src/main/java/com/example/pickyourfood/place/PlaceService.java`
- Modify: `src/main/java/com/example/pickyourfood/place/PlaceController.java`
- Modify: `src/main/java/com/example/pickyourfood/account/SecurityConfig.java:19`
- Test: `src/test/java/com/example/pickyourfood/place/PlaceServiceTest.java`
- Test: `src/test/java/com/example/pickyourfood/place/PlaceControllerTest.java`

**Interfaces:**
- Consumes: Task 1의 `courseAsync(Stop, List<KakaoPlace>, List<KakaoPlace>, Set<String>)`, 기존 `legAsync`, `route`, `routeName`.
- Produces:
  - `PlacesResponse.Walk(List<Leg> legs, String routeUrl)`.
  - `PlaceService.Stop(String id, String name, double lat, double lng)` — 패키지 공개 record(`private` 제거).
  - `public PlacesResponse.Walk PlaceService.walk(List<PlaceService.Stop> stops)`.
  - `GET /api/places/legs?id=&name=&lat=&lng=`(각 2~3번 반복, 순서대로) → JSON `{"legs": [Leg], "routeUrl": string|null}`, 로그인 불필요.

- [ ] **Step 1: Write the failing service tests**

`PlaceServiceTest.java`에 import `com.example.pickyourfood.place.PlaceService.Stop;`와 `com.example.pickyourfood.place.PlacesResponse.Walk;`를 더하고, `pickedCourseWithAFakeLocationDoesNotPoisonTheRouteCacheForTheRealRestaurant` 바로 아래에 넣는다:

```java
	@Test
	void walkJoinsTheStopsInOrder() {
		Walk walk = service.walk(List.of(
				new Stop("r1", "식당", 37.56, 127.05), new Stop("c2", "다른 카페", 37.564, 127.05), new Stop("s1", "볼거리", 37.566, 127.05)));

		assertThat(walk.legs()).extracting(Leg::from).containsExactly("식당", "다른 카페");
		assertThat(walk.legs()).extracting(Leg::to).containsExactly("다른 카페", "볼거리");
		assertThat(walk.routeUrl()).isEqualTo(PlaceService.ROUTE_BASE
				+ "%EC%8B%9D%EB%8B%B9,37.56,127.05/%EB%8B%A4%EB%A5%B8%20%EC%B9%B4%ED%8E%98,37.564,127.05/%EB%B3%BC%EA%B1%B0%EB%A6%AC,37.566,127.05");
	}

	@Test
	void walkWithTwoStopsHasOneLeg() {
		Walk walk = service.walk(List.of(new Stop("r1", "식당", 37.56, 127.05), new Stop("s1", "볼거리", 37.566, 127.05)));

		assertThat(walk.legs()).extracting(Leg::to).containsExactly("볼거리");
	}

	@Test
	void walkWithAFakeCafeLocationDoesNotPoisonTheRouteCacheForTheRealCafe() {
		// a client reuses a real cafe's id with coordinates nowhere near it
		when(tmap.route(37.56, 127.05, 37.99, 127.05)).thenReturn(Optional.of(new Route(1, 60, WALK)));
		service.walk(List.of(new Stop("r1", "r1", 37.56, 127.05), new Stop("cafe", "가짜 카페", 37.99, 127.05)));

		famousRanked("r1");
		cafesNear(37.56, spot("cafe", 37.564));
		when(tmap.route(37.56, 127.05, 37.564, 127.05)).thenReturn(Optional.of(new Route(512, 421, WALK)));

		Leg leg = service.search("ramen", ORIGIN).dateCourses().get(0).legs().get(0);

		assertThat(leg.meters()).isEqualTo(512);
	}
```

- [ ] **Step 2: Write the failing controller tests**

`PlaceControllerTest.java`에 import `com.example.pickyourfood.place.PlaceService.Stop;`, `com.example.pickyourfood.place.PlacesResponse.Walk;`, `org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;`를 더하고, 클래스 끝(`mapKey` 관련 테스트 앞이든 뒤든 마지막)에 넣는다:

```java
	// stops as "|"-separated values per parameter, in order
	private static MockHttpServletRequestBuilder legs(String ids, String names, String lats, String lngs) {
		return get("/api/places/legs")
				.param("id", ids.split("\\|", -1)).param("name", names.split("\\|", -1))
				.param("lat", lats.split("\\|", -1)).param("lng", lngs.split("\\|", -1));
	}

	@Test
	void walkNeedsNoLogin() throws Exception {
		Leg first = new Leg("고른 식당", "어니언, 성수", 445, 7, null);
		Leg second = new Leg("어니언, 성수", "서울숲", 600, 9, null);
		when(places.walk(List.of(new Stop("p1", "고른 식당", 37.55, 127.05), new Stop("c1", "어니언, 성수", 37.554, 127.05),
				new Stop("s1", "서울숲", 37.559, 127.05))))
				.thenReturn(new Walk(List.of(first, second), "https://map.kakao.com/link/by/walk/a"));

		mvc.perform(legs("p1|c1|s1", " 고른 식당 |어니언, 성수|서울숲", "37.55|37.554|37.559", "127.05|127.05|127.05"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.legs[0].to").value("어니언, 성수"))
				.andExpect(jsonPath("$.legs[1].walkMinutes").value(9))
				.andExpect(jsonPath("$.routeUrl").value("https://map.kakao.com/link/by/walk/a"));
	}

	@Test
	void walkChecksItsInput() throws Exception {
		MockHttpServletRequestBuilder[] bad = {
				legs("p1", "식당", "37.55", "127.05"),
				legs("a|b|c|d", "가|나|다|라", "37.55|37.55|37.55|37.55", "127.05|127.05|127.05|127.05"),
				legs("p1|c1", "식당", "37.55|37.554", "127.05|127.05"),
				legs("p1|c1", "식당|카페", "37.55", "127.05|127.05"),
				legs(" |c1", "식당|카페", "37.55|37.554", "127.05|127.05"),
				legs("p1|c1", "식당|" + "가".repeat(101), "37.55|37.554", "127.05|127.05"),
				legs("p1|c1", "식당|카페\n", "37.55|37.554", "127.05|127.05"),
				legs("p1|c1", "식당|카페", "37.55|91", "127.05|127.05"),
				legs("p1|c1", "식당|카페", "37.55|37.554", "127.05|-181"),
				legs("p1|c1", "식당|카페", "37.55|NaN", "127.05|127.05"),
				legs("p1|c1", "식당|카페", "37.55|north", "127.05|127.05"),
				get("/api/places/legs").param("id", "p1", "c1").param("name", "식당", "카페").param("lat", "37.55", "37.554"),
		};
		for (MockHttpServletRequestBuilder request : bad) mvc.perform(request).andExpect(status().isBadRequest());
		verifyNoInteractions(places);
	}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests '*place*'`
Expected: 컴파일 실패(`Walk`, `walk`, `Stop` 접근 불가).

- [ ] **Step 4: Implement**

`PlacesResponse.java`의 `Course` 아래에 더한다:

```java
	// the walk through stops the client chose: what GET /api/places/legs answers, with the same fields and nulls as DateCourse
	public record Walk(List<Leg> legs, String routeUrl) {
	}
```

`PlaceService.java`:
- import `com.example.pickyourfood.place.PlacesResponse.Walk;`를 더한다.
- `private record Stop(String id, String name, double lat, double lng) {` 를 `record Stop(String id, String name, double lat, double lng) {` 로 바꾼다.
- `courseAsync`에서 구간 부분을 `walkAsync`로 떼고, `walk`를 더한다(`course` 메서드 바로 아래):

```java
	// legs and route through stops the client chose (a swapped cafe or sight), in order
	public Walk walk(List<Stop> stops) {
		return walkAsync(stops).join();
	}
```

```java
	// picks the cafe and sight no earlier course uses (see pick) and offers every candidate for swapping
	private CompletableFuture<Course> courseAsync(Stop start, List<KakaoPlace> cafes, List<KakaoPlace> sights, Set<String> used) {
		Spot cafe = pick(cafes, used);
		Spot sight = pick(sights, used);
		List<Stop> stops = new ArrayList<>();
		stops.add(start);
		if (cafe != null) stops.add(new Stop(cafe.id(), cafe.name(), cafe.lat(), cafe.lng()));
		if (sight != null) stops.add(new Stop(sight.id(), sight.name(), sight.lat(), sight.lng()));
		return walkAsync(stops).thenApply(walk -> new Course(cafe, sight, walk.legs(), walk.routeUrl(), spots(cafes), spots(sights)));
	}

	private CompletableFuture<Walk> walkAsync(List<Stop> stops) {
		List<CompletableFuture<Leg>> legs = IntStream.range(1, stops.size())
				.mapToObj(i -> legAsync(stops.get(i - 1), stops.get(i))).toList();
		String routeUrl = legs.isEmpty() ? null
				: ROUTE_BASE + stops.stream()
						.map(stop -> routeName(stop.name()) + "," + stop.lat() + "," + stop.lng())
						.collect(Collectors.joining("/"));
		return CompletableFuture.allOf(legs.toArray(CompletableFuture[]::new))
				.thenApply(done -> new Walk(legs.stream().map(CompletableFuture::join).toList(), routeUrl));
	}
```

- `route`의 캐시 키와 주석을 바꾼다:

```java
		// both ends with their coordinates, so client-chosen stops can't poison the entry a real course reads
		String key = from.id() + "@" + from.lat() + "," + from.lng() + ">" + to.id() + "@" + to.lat() + "," + to.lng();
```

`PlaceController.java`:
- import `com.example.pickyourfood.place.PlaceService.Stop;`, `com.example.pickyourfood.place.PlacesResponse.Walk;`, `java.util.stream.IntStream;`를 더한다.
- `course`의 검사를 `within`으로 바꾸고, `legs`와 `within`을 더한다:

```java
	// the client sends the restaurant it already has: Kakao has no lookup by id
	@GetMapping("/api/places/course")
	Course course(@RequestParam String id, @RequestParam String name, @RequestParam double lat, @RequestParam double lng) {
		if (!fits(id) || !fits(name) || !within(lat, lng)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id, name, lat or lng is invalid");
		}
		return places.course(id.strip(), name.strip(), lat, lng);
	}

	// the stops on screen in order (the restaurant, then the cafe and sight that exist), each parameter repeated per stop;
	// arrays, not lists: a repeated parameter is never split at commas, so a name like "어니언, 성수" stays whole
	@GetMapping("/api/places/legs")
	Walk legs(@RequestParam String[] id, @RequestParam String[] name, @RequestParam double[] lat, @RequestParam double[] lng) {
		int size = id.length;
		boolean valid = size >= 2 && size <= 3 && name.length == size && lat.length == size && lng.length == size
				&& IntStream.range(0, size).allMatch(i -> fits(id[i]) && fits(name[i]) && within(lat[i], lng[i]));
		if (!valid) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "2 or 3 stops with a valid id, name, lat and lng are required");
		return places.walk(IntStream.range(0, size).mapToObj(i -> new Stop(id[i].strip(), name[i].strip(), lat[i], lng[i])).toList());
	}

	private static boolean fits(String value) {
		return !value.isBlank() && value.strip().length() <= 100 && value.chars().noneMatch(Character::isISOControl);
	}

	// written as "within" so NaN, which fails every comparison, is rejected too
	private static boolean within(double lat, double lng) {
		return Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
	}
```

`SecurityConfig.java:19`: `"/api/places/course",` 뒤에 `"/api/places/legs",`를 더한다.

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test`
Expected: BUILD SUCCESSFUL, 실패 0. `walkWithAFakeCafeLocationDoesNotPoisonTheRouteCacheForTheRealCafe`는 Step 4 전(키에 도착지 좌표 없음)에는 `expected 512 but was 1`로 실패해야 한다 — 확인하려면 캐시 키를 바꾸기 전에 한 번 돌려 본다.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/pickyourfood/place/PlacesResponse.java src/main/java/com/example/pickyourfood/place/PlaceService.java src/main/java/com/example/pickyourfood/place/PlaceController.java src/main/java/com/example/pickyourfood/account/SecurityConfig.java src/test/java/com/example/pickyourfood/place/PlaceServiceTest.java src/test/java/com/example/pickyourfood/place/PlaceControllerTest.java
git commit -m "feat: walk legs again through a swapped cafe or sight

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 코스 탭에서 카페·볼거리 바꾸기 (화면)

**Files:**
- Modify: `frontend/src/shared/places/types.ts`
- Modify: `frontend/src/shared/places/DateCourses.tsx`
- Modify: `frontend/src/features/places/api.ts`
- Modify: `frontend/src/features/places/PlacesSection.tsx`
- Modify: `frontend/src/features/saved/api.ts`

**Interfaces:**
- Consumes: Task 1의 JSON `cafes`·`sights`·`distanceMeters`, Task 2의 `GET /api/places/legs` → `{legs, routeUrl}`.
- Produces:
  - `export type SpotSwapper = { change: (course: number, kind: 'cafe' | 'sight', spot: Spot) => void; pending: number | null; failed: number | null }` (`shared/places/DateCourses.tsx`).
  - `DateCourses` prop `swap?: SpotSwapper`.
  - `fetchLegs(stops: Stop[]): Promise<Walk>` (`features/places/api.ts`).

- [ ] **Step 1: Types**

`types.ts`의 `Spot`에 `kakaoUrl: string` 아래로 더한다(주석은 타입 위에):

```ts
// distanceMeters is Kakao's distance from the course's restaurant; saved results have none
export type Spot = {
  id: string
  name: string
  category: string
  address: string
  lat: number
  lng: number
  kakaoUrl: string
  distanceMeters?: number
}
```

`DateCourse`의 주석과 필드:

```ts
// legs join only the stops that exist; routeUrl is null when the restaurant is the only stop;
// picked is true for the course built from a restaurant the user chose (missing or null for the others);
// cafes and sights are the nearest candidates to swap to, only in a fresh search (saved results have none)
export type DateCourse = {
  restaurant: Place
  cafe: Spot | null
  sight: Spot | null
  legs: Leg[]
  routeUrl: string | null
  picked?: boolean | null
  cafes?: Spot[]
  sights?: Spot[]
}
```

- [ ] **Step 2: API call**

`features/places/api.ts` 끝에 더한다:

```ts
type Stop = Pick<Spot, 'id' | 'name' | 'lat' | 'lng'>
export type Walk = Pick<DateCourse, 'legs' | 'routeUrl'>

// the stops in order (the restaurant, then the cafe and sight that exist), each parameter repeated per stop
export function fetchLegs(stops: Stop[]): Promise<Walk> {
  const params = new URLSearchParams()
  for (const stop of stops) {
    params.append('id', stop.id)
    params.append('name', stop.name)
    params.append('lat', String(stop.lat))
    params.append('lng', String(stop.lng))
  }
  return request(`/api/places/legs?${params}`)
}
```

import를 `import type { DateCourse, Place, Places, Spot } from '../../shared/places/types.ts'`로 바꾼다.

- [ ] **Step 3: Candidate list in the course tab**

`DateCourses.tsx`:

import 줄을 바꾼다:

```ts
import { ArrowsClockwise, Coffee, ForkKnife, MapTrifold, Mountains, PersonSimpleWalk } from '@phosphor-icons/react'
```

```ts
import type { DateCourse, Leg, Spot } from './types.ts'
```

`Step` 타입과 그 위에 둘 타입:

```ts
type Kind = 'cafe' | 'sight'

// lets a search swap a course's cafe or sight for another candidate (values are course indexes); the saved view leaves it out
export type SpotSwapper = { change: (course: number, kind: Kind, spot: Spot) => void; pending: number | null; failed: number | null }

// leg is the walk into this step, shown above it; choice is the candidates a cafe or sight can be swapped to
type Step = { icon: Icon; label: string; leg: string | null } & (
  | { name: string; detail: string; url: string; choice?: { kind: Kind; current: string; candidates: Spot[] } }
  | { missing: string }
)
```

컴포넌트 시그니처와 상태, 단계 목록:

```tsx
// starts on the initial course (the first by default); PlacesSection remounts this for every new search and picked course
export default function DateCourses({ courses, initial = 0, swap }: { courses: DateCourse[]; initial?: number; swap?: SpotSwapper }) {
  const [selected, setSelected] = useState(initial)
  // which step's candidate list is open; one at a time, and closed on another tab
  const [open, setOpen] = useState<Kind | null>(null)
  const { restaurant, cafe, sight, cafes = [], sights = [], legs, routeUrl, picked } = courses[selected]
  const busy = swap?.pending === selected
  const steps: Step[] = [
    { icon: ForkKnife, label: '식사', leg: null, name: restaurant.name, detail: restaurant.address, url: restaurant.kakaoUrl },
    cafe
      ? {
          icon: Coffee,
          label: '카페',
          leg: walk(legs[0], false),
          name: cafe.name,
          detail: cafe.category,
          url: cafe.kakaoUrl,
          choice: { kind: 'cafe', current: cafe.id, candidates: cafes },
        }
      : { icon: Coffee, label: '카페', leg: null, missing: '근처에 카페를 찾지 못했어요' },
    sight
      ? // the sight's leg is always the last one; without a cafe it starts at the restaurant
        {
          icon: Mountains,
          label: '볼거리',
          leg: walk(legs[legs.length - 1], !cafe),
          name: sight.name,
          detail: sight.category,
          url: sight.kakaoUrl,
          choice: { kind: 'sight', current: sight.id, candidates: sights },
        }
      : { icon: Mountains, label: '볼거리', leg: null, missing: '근처에 볼거리를 찾지 못했어요' },
  ]
```

탭 버튼의 `onClick`:

```tsx
              onClick={() => {
                setSelected(index)
                setOpen(null)
              }}
```

구간 줄 안쪽(`{step.leg && (...)}`)의 `<PersonSimpleWalk size={14} /> {step.leg}`를 바꾼다:

```tsx
                    <PersonSimpleWalk size={14} /> {busy ? '경로 다시 계산 중…' : step.leg}
```

찾은 장소 칸(`'missing' in step ? ... : (<>...</>)`의 뒤쪽)을 바꾼다:

```tsx
                <>
                  <p className="mt-3 text-xl font-semibold tracking-tight">{step.name}</p>
                  <p className="mt-1 text-sm text-zinc-500">{step.detail}</p>
                  <div className="mt-4 flex flex-wrap gap-2">
                    <MapLink href={step.url} label="카카오맵" />
                    {swap && step.choice && step.choice.candidates.length > 1 && (
                      <SwapButton
                        kind={step.choice.kind}
                        open={open === step.choice.kind}
                        disabled={busy}
                        onToggle={(kind) => setOpen(open === kind ? null : kind)}
                      />
                    )}
                  </div>
                  {swap && step.choice && open === step.choice.kind && (
                    <Candidates
                      choice={step.choice}
                      onPick={(kind, spot) => {
                        setOpen(null)
                        swap.change(selected, kind, spot)
                      }}
                    />
                  )}
                </>
```

`</AnimatePresence>` 바로 아래(지도 앞)에 실패 문구:

```tsx
      {swap?.failed === selected && (
        <p role="alert" className="mt-6 text-sm text-accent">
          바꾸지 못했어요. 다시 시도해 주세요.
        </p>
      )}
```

파일 끝에 두 컴포넌트를 더한다:

```tsx
function SwapButton({ kind, open, disabled, onToggle }: { kind: Kind; open: boolean; disabled: boolean; onToggle: (kind: Kind) => void }) {
  return (
    <button
      type="button"
      onClick={() => onToggle(kind)}
      disabled={disabled}
      aria-expanded={open}
      className="flex w-fit items-center gap-2 rounded-full border border-accent px-4 py-2 text-sm font-medium text-accent transition active:scale-[0.98] disabled:opacity-40"
    >
      <ArrowsClockwise size={16} /> 다른 곳
    </button>
  )
}

function Candidates({ choice, onPick }: { choice: { kind: Kind; current: string; candidates: Spot[] }; onPick: (kind: Kind, spot: Spot) => void }) {
  return (
    <ul className="mt-4 divide-y divide-zinc-200 border-y border-zinc-200">
      {choice.candidates.map((spot) => {
        const current = spot.id === choice.current
        return (
          <li key={spot.id}>
            <button
              type="button"
              onClick={() => onPick(choice.kind, spot)}
              disabled={current}
              className="flex w-full items-baseline justify-between gap-3 py-3 text-left transition hover:text-accent disabled:hover:text-inherit"
            >
              <span className="min-w-0">
                <span className="block truncate text-sm font-medium">{spot.name}</span>
                <span className="block truncate text-xs text-zinc-500">{spot.category}</span>
              </span>
              <span className="shrink-0 font-mono text-xs text-zinc-500">
                {current && <span className="text-accent">지금 </span>}
                {spot.distanceMeters !== undefined && distance(spot.distanceMeters)}
              </span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}
```

- [ ] **Step 4: Swap state in the search section**

`PlacesSection.tsx`:

import 두 줄을 바꾼다:

```ts
import DateCourses from '../../shared/places/DateCourses.tsx'
import type { SpotSwapper } from '../../shared/places/DateCourses.tsx'
```

```ts
import type { DateCourse, Place, Places } from '../../shared/places/types.ts'
import { fetchCourse, fetchLegs, fetchPlaces } from './api.ts'
```

`latestCourse` 선언의 주석과 그 위 상태를 바꾼다(`picking` 선언 바로 아래에):

```ts
  // the swap request in flight or the one that failed, by course index
  const [swapping, setSwapping] = useState<{ pending: number | null; failed: number | null }>({ pending: null, failed: null })
```

```ts
  // picked courses and swaps share one counter: of the two, only the last click counts
  const latestCourse = useRef(0)
```

`search` 안의 `setPicking({ pending: null, failed: null })` 아래에 더한다:

```ts
    setSwapping({ pending: null, failed: null })
```

`pick` 안의 두 `setPicking({ pending: null, failed: null })`(기존 코스 분기)과 `setPicking({ pending: place.id, failed: null })`(요청 분기) 앞에 각각 더한다:

```ts
      setSwapping({ pending: null, failed: null })
```

(요청 분기는 들여쓰기 4칸: `    setSwapping({ pending: null, failed: null })`.)

`pick` 아래에 `change`를 더한다:

```ts
  // the swapped spot shows only with its new legs, so a dropped or failed request leaves the course as it was
  const change: SpotSwapper['change'] = (index, kind, spot) => {
    if (state.status !== 'done') return
    const data = state.data
    const id = ++latestCourse.current
    const searchId = latest.current
    const current = () => id === latestCourse.current && searchId === latest.current
    const course: DateCourse = { ...data.dateCourses[index], [kind]: spot }
    setPicking({ pending: null, failed: null })
    setSwapping({ pending: index, failed: null })
    fetchLegs([course.restaurant, course.cafe, course.sight].filter((stop) => stop !== null)).then(
      (walk) => {
        if (!current()) return
        const next = { ...data, dateCourses: data.dateCourses.map((other, i) => (i === index ? { ...course, ...walk } : other)) }
        setState({ status: 'done', data: next })
        onLoaded(next)
        setSwapping({ pending: null, failed: null })
      },
      () => current() && setSwapping({ pending: null, failed: index }),
    )
  }
```

`DateCourses` 사용처:

```tsx
                <DateCourses key={shown.version} courses={state.data.dateCourses} initial={shown.index} swap={{ change, ...swapping }} />
```

- [ ] **Step 5: Leave candidates out of a save**

`features/saved/api.ts`:

import를 `import type { DateCourse, Place, Places, Spot } from '../../shared/places/types.ts'`로 바꾸고, `kakaoOnly` 아래에 더한다:

```ts
// candidates only serve swapping on the search screen, and the saved format keeps no distance for a spot
function savedCourse({ restaurant, cafe, sight, legs, routeUrl, picked }: DateCourse): DateCourse {
  return { restaurant, cafe: cafe && savedSpot(cafe), sight: sight && savedSpot(sight), legs, routeUrl, picked }
}

function savedSpot({ id, name, category, address, lat, lng, kakaoUrl }: Spot): Spot {
  return { id, name, category, address, lat, lng, kakaoUrl }
}
```

`saveResult`의 본문:

```ts
    body: JSON.stringify({
      ...saved,
      places: saved.places && mapPlaces({ ...saved.places, dateCourses: saved.places.dateCourses.map(savedCourse) }, kakaoOnly),
    }),
```

- [ ] **Step 6: Build and lint**

Run: `cd frontend && npm run build && npm run lint`
Expected: 둘 다 오류 0.

- [ ] **Step 7: Headless check**

확인 스크립트와 스크린샷은 저장소 밖 또는 git-ignored 작업 폴더(`.superpowers/sdd/2026-10-07-swap-spots/ui/`)에 둔다. 출발점: G1 드라이버 `.superpowers/tools/cdp.mjs`(CDP `Fetch` 도메인으로 `/api/*`와 `dapi.kakao.com`을 가로채 준비한 JSON을 돌려준다, 가짜 `kakao.maps` 포함). 복사해서 `SCRATCH` 경로와 시나리오를 바꾼다. Vite는 `cd frontend && npx vite --port 5174 --strictPort`, Chrome은 `--headless=new --remote-debugging-port=9222`. 끝나면 둘 다 `pkill`.

가로챌 데이터: `/api/places`가 자동 코스 2개를 돌려주고, 각 코스에 `cafes` 3곳(`distanceMeters` 포함, 첫째가 `cafe`)과 `sights` 2곳. `/api/places/legs`는 요청 URL의 `name` 파라미터로 `legs`(`from`·`to`가 그 이름들)와 `routeUrl`(`.../walk/<이름들>`)을 만들어 돌려주고, 시나리오에 따라 늦게(setTimeout) 또는 500으로 답한다. 저장 확인은 `/api/me`를 로그인 사용자로 답하고 `POST /api/saved`의 본문을 기록한 뒤 `{ "id": "test" }`를 돌려준다.

확인 항목(모두 PASS):
1. 첫 코스 카페 칸의 `다른 곳` → 후보 3줄, 첫 줄에 `지금`과 거리(예: `120m`), 그 줄 버튼 disabled → 둘째 후보 클릭 → 목록 닫힘, 구간 줄에 `경로 다시 계산 중…`, 카페 이름은 아직 이전 것 → 응답 후 카페 이름·구간 문구(`도보 N분`)·`전체 경로 보기` href가 새 카페로.
2. `/api/places/legs` 500 → 카페 이름 그대로, `바꾸지 못했어요. 다시 시도해 주세요.`(role="alert") 보임, 다시 바꾸면 성공하고 알림 사라짐.
3. 바꾸기 A(응답 1.5초 지연) 직후 같은 코스에서 바꾸기 B(즉시 응답) → 끝난 뒤 B의 카페만 남는다(A 응답이 늦게 와도 덮어쓰지 않음).
4. 바꾼 뒤 저장 → 기록한 `POST /api/saved` 본문의 코스에 `cafes`·`sights` 키가 없고, `cafe`에 `distanceMeters`가 없고, `cafe.id`는 바꾼 카페.
5. 첫 코스 카페 목록을 연 채 둘째 코스 탭 → 목록 닫힘. 다시 첫 코스 탭 → 바꾼 카페가 그대로.
6. 저장 결과 화면(`/s/test`, 가로챈 `/api/saved/test`)에는 `다른 곳` 버튼이 없다.
7. 뷰포트 375px에서 후보 목록을 연 상태로 `document.documentElement.scrollWidth <= 375`.
8. 바꾸기(응답 1.5초 지연) 직후 맛집 표에서 다른 식당으로 `이 식당으로 코스 만들기`(즉시 응답) → 내 코스가 생기고, 늦게 온 바꾸기 응답은 반영되지 않는다(첫 자동 코스의 카페 그대로). 반대로 내 코스 만들기(지연) 직후 바꾸기(즉시) → 바꾼 카페가 남고 내 코스는 생기지 않는다.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/shared/places/types.ts frontend/src/shared/places/DateCourses.tsx frontend/src/features/places/api.ts frontend/src/features/places/PlacesSection.tsx frontend/src/features/saved/api.ts
git commit -m "feat: swap a course's cafe or sight for a nearby candidate

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
