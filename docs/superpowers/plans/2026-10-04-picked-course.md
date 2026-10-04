# G1. 내가 고른 식당으로 데이트 코스 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 맛집 표에서 고른 식당 하나로 식사 → 카페 → 볼거리 코스를 만들어 코스 탭 맨 앞에 `내 코스`로 보여 주고, 저장·공유에도 남긴다.

**Architecture:** 서버 `place` 기능에 `GET /api/places/course`를 더한다. 화면이 보낸 식당 `id`·`name`·`lat`·`lng`로 기존 코스 코드(카페·볼거리 후보, TMAP 구간, 직선 대체)를 그대로 돌려 식당을 뺀 코스 부분 `Course`를 돌려준다. 화면은 `features/places/`에서 요청하고 받은 코스에 `restaurant`와 `picked: true`를 붙여 `dateCourses` 맨 앞에 넣는다. 저장 형식은 `picked`를 보관하고 코스 상한을 4개로 올린다.

**Tech Stack:** Spring Boot 4.1.1, Java 17, Jackson 3, MockMvc, Mockito; React + Vite + TypeScript, Tailwind v4, Phosphor icons, motion/react.

**Spec:** `docs/superpowers/specs/2026-10-04-picked-course-design.md`

## Global Constraints

- Gradle 명령은 모두 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` 뒤에 실행한다.
- Jackson 3: databind는 `tools.jackson.databind.*`, 애너테이션은 `com.fasterxml.jackson.annotation.*`.
- package-by-feature. 백엔드는 `place`·`saved`·`account/SecurityConfig`만, 프론트엔드는 `features/places/`·`shared/places/`만 바뀐다. 기능끼리 import하지 않는다. `features/saved/`는 바꾸지 않는다.
- 새 의존성 없음. 아이콘은 `@phosphor-icons/react`만, 이모지 금지. 강조색은 Tailwind `accent`(`oklch(0.6 0.15 32)`).
- 문구(그대로): `이 식당으로 코스 만들기`, `코스 만드는 중…`, `코스를 만들지 못했어요. 다시 시도해 주세요.`, `내 코스`, `내 데이트 코스`.
- 입력 검사: `id`·`name`은 공백 제거 후 비어 있지 않고 100자 이하, `lat`은 -90~90, `lng`는 -180~180. 위반은 400.
- 내 코스는 한 번에 하나. 저장 결과의 코스는 최대 4개, `picked: true`는 최대 1개.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- `.claude/`, `.serena/`, `graphify-out/`, `data/`, `.superpowers/`는 절대 `git add`하지 않는다.

## Review Focus

1. **`lat=NaN`·`Infinity`** — 범위 비교가 NaN에서 거짓이 되어 통과하면 안 된다. 400. 테스트: Task 1 `pickedCourseChecksItsInput`.
2. **코스 요청 중 다른 식당을 또 누름** — 나중에 누른 식당의 코스만 남는다. 먼저 끝난 이전 요청이 덮어쓰면 안 된다. 확인: Task 3 헤드리스 확인 7.
3. **코스 요청 중 새 검색이나 현재 위치 찾기** — 이전 검색의 코스가 새 결과에 끼어들거나, 위치 찾는 중 화면을 이전 결과로 되돌리면 안 된다. 확인: Task 3 헤드리스 확인 8.
4. **조작된 저장 요청**(코스 5개, `picked` 코스 2개) — 400, 저장 안 됨. 테스트: Task 2 `pickedCourseIsKeptAndAtMostOneOfFourCourses`.
5. **`picked` 없는 예전 저장 결과** — 그대로 열리고 `내 코스` 표시가 없다. 테스트: Task 2(기존 `RESULT`의 `picked` 비어 있음), 화면은 바뀌지 않는 `features/saved/`가 `!course.picked`로 판단.

---

### Task 1: 고른 식당의 코스 API

**Files:**
- Modify: `src/main/java/com/example/pickyourfood/place/PlacesResponse.java`
- Modify: `src/main/java/com/example/pickyourfood/place/PlaceService.java`
- Modify: `src/main/java/com/example/pickyourfood/place/PlaceController.java`
- Modify: `src/main/java/com/example/pickyourfood/account/SecurityConfig.java:19`
- Test: `src/test/java/com/example/pickyourfood/place/PlaceServiceTest.java`
- Test: `src/test/java/com/example/pickyourfood/place/PlaceControllerTest.java`

**Interfaces:**
- Consumes: 기존 `PlaceService`의 `candidatesAsync`, `pick`, `courseAsync`, `legAsync`, `Stop`.
- Produces:
  - `PlacesResponse.Course(Spot cafe, Spot sight, List<Leg> legs, String routeUrl)`.
  - `public PlacesResponse.Course PlaceService.course(String id, String name, double lat, double lng)`.
  - `GET /api/places/course?id=&name=&lat=&lng=` → JSON `{"cafe": Spot|null, "sight": Spot|null, "legs": [Leg], "routeUrl": string|null}`, 로그인 불필요.

- [ ] **Step 1: Write the failing service tests**

`PlaceServiceTest.java`에 import `com.example.pickyourfood.place.PlacesResponse.Course;`를 더하고, `unknownPlaceIsNotFound` 테스트 바로 아래에 넣는다:

```java
	@Test
	void pickedRestaurantGetsTheNearestCafeAndSight() {
		cafesNear(37.55, spot("cafe", 37.554), spot("far cafe", 37.556));
		sightsNear(37.55, spot("sight", 37.559));

		Course course = service.course("p1", "고른 식당", 37.55, 127.05);

		assertThat(course.cafe().id()).isEqualTo("cafe");
		assertThat(course.sight().id()).isEqualTo("sight");
		assertThat(course.legs()).extracting(Leg::from).containsExactly("고른 식당", "cafe");
		assertThat(course.routeUrl()).startsWith(PlaceService.ROUTE_BASE + "%EA%B3%A0%EB%A5%B8%20%EC%8B%9D%EB%8B%B9,37.55,127.05/");
	}

	@Test
	void pickedRestaurantWithAFailedCafeSearchWalksToTheSight() {
		when(kakao.nearby(KakaoClient.CAFE, 37.55, 127.05, PlaceService.CAFE_RADIUS))
				.thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
		sightsNear(37.55, spot("sight", 37.559));

		Course course = service.course("p1", "고른 식당", 37.55, 127.05);

		assertThat(course.cafe()).isNull();
		assertThat(course.legs()).extracting(Leg::from).containsExactly("고른 식당");
		assertThat(course.legs()).extracting(Leg::to).containsExactly("sight");
	}

	@Test
	void pickedRestaurantWithNothingNearbyHasNoLegsOrRoute() {
		Course course = service.course("p1", "고른 식당", 37.55, 127.05);

		assertThat(course.cafe()).isNull();
		assertThat(course.sight()).isNull();
		assertThat(course.legs()).isEmpty();
		assertThat(course.routeUrl()).isNull();
	}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.place.PlaceServiceTest'`
Expected: compilation FAIL — `Course` and `course(...)` do not exist.

- [ ] **Step 3: Add the `Course` record**

`PlacesResponse.java`의 `DateCourse` 레코드 아래에:

```java
	// a course without its restaurant: what GET /api/places/course answers for a restaurant the user picked,
	// with the same fields and nulls as DateCourse
	public record Course(Spot cafe, Spot sight, List<Leg> legs, String routeUrl) {
	}
```

- [ ] **Step 4: Build courses from a `Stop` and add `course(...)`**

`PlaceService.java`에서 import `com.example.pickyourfood.place.PlacesResponse.Course;`를 더한다.

`dateCourses`의 반복문 안 `courses.add(courseAsync(restaurants.get(i), cafe, sight));`를 바꾸고, `candidatesAsync` 두 호출이 `Place` 대신 `Stop`을 받게 한다. `dateCourses` 전체는 이렇게 된다:

```java
	private List<DateCourse> dateCourses(List<Place> famous) {
		List<Place> restaurants = famous.stream().limit(COURSES).toList();
		// start every Kakao lookup before waiting on any of them
		List<CompletableFuture<List<KakaoPlace>>> cafes = restaurants.stream()
				.map(restaurant -> candidatesAsync(KakaoClient.CAFE, stop(restaurant), CAFE_RADIUS)).toList();
		List<CompletableFuture<List<KakaoPlace>>> sights = restaurants.stream()
				.map(restaurant -> candidatesAsync(KakaoClient.SIGHT, stop(restaurant), SIGHT_RADIUS)).toList();
		// picked in course order, so an earlier course keeps its nearest spot
		Set<String> used = new HashSet<>();
		List<CompletableFuture<DateCourse>> courses = new ArrayList<>();
		for (int i = 0; i < restaurants.size(); i++) {
			Place restaurant = restaurants.get(i);
			Spot cafe = pick(cafes.get(i).join(), used);
			Spot sight = pick(sights.get(i).join(), used);
			courses.add(courseAsync(stop(restaurant), cafe, sight)
					.thenApply(found -> new DateCourse(restaurant, found.cafe(), found.sight(), found.legs(), found.routeUrl())));
		}
		return courses.stream().map(CompletableFuture::join).toList();
	}

	// a course from a restaurant the user picked: the nearest cafe and sight, even when a search's courses use them
	public Course course(String id, String name, double lat, double lng) {
		Stop start = new Stop(id, name, lat, lng);
		CompletableFuture<List<KakaoPlace>> cafes = candidatesAsync(KakaoClient.CAFE, start, CAFE_RADIUS);
		CompletableFuture<List<KakaoPlace>> sights = candidatesAsync(KakaoClient.SIGHT, start, SIGHT_RADIUS);
		return courseAsync(start, pick(cafes.join(), new HashSet<>()), pick(sights.join(), new HashSet<>())).join();
	}

	private static Stop stop(Place place) {
		return new Stop(place.id(), place.name(), place.lat(), place.lng());
	}
```

`candidatesAsync`의 매개변수 `Place from`을 `Stop from`으로 바꾼다(본문은 `from.lat()`, `from.lng()`, `from.name()`만 쓰므로 그대로).

`courseAsync`를 출발 `Stop`을 받아 `Course`를 돌려주도록 바꾼다:

```java
	private CompletableFuture<Course> courseAsync(Stop start, Spot cafe, Spot sight) {
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
				.thenApply(done -> new Course(cafe, sight, legs.stream().map(CompletableFuture::join).toList(), routeUrl));
	}
```

- [ ] **Step 5: Run the service tests**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.place.PlaceServiceTest'`
Expected: PASS — the 3 new tests and every existing course test (the refactor keeps their behaviour).

- [ ] **Step 6: Write the failing controller tests**

`PlaceControllerTest.java`에 import `com.example.pickyourfood.place.PlacesResponse.Course;`와 `com.example.pickyourfood.place.PlacesResponse.Spot;`를 더하고(이미 있는 import는 두지 않는다), `mapKeyNeedsNoLogin` 위에 넣는다:

```java
	@Test
	void pickedCourseNeedsNoLogin() throws Exception {
		Spot cafe = new Spot("c1", "어니언", "카페", "", 37.554, 127.05, "");
		Leg leg = new Leg("고른 식당", "어니언", 445, 7, null);
		when(places.course("p1", "고른 식당", 37.55, 127.05))
				.thenReturn(new Course(cafe, null, List.of(leg), "https://map.kakao.com/link/by/walk/a"));

		mvc.perform(get("/api/places/course").param("id", "p1").param("name", " 고른 식당 ").param("lat", "37.55").param("lng", "127.05"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cafe.name").value("어니언"))
				.andExpect(jsonPath("$.sight").value(nullValue()))
				.andExpect(jsonPath("$.legs[0].walkMinutes").value(7))
				.andExpect(jsonPath("$.routeUrl").value("https://map.kakao.com/link/by/walk/a"))
				.andExpect(jsonPath("$.restaurant").doesNotExist());
	}

	@Test
	void pickedCourseChecksItsInput() throws Exception {
		String[][] bad = {
				{ " ", "식당", "37.55", "127.05" },
				{ "p1", " ", "37.55", "127.05" },
				{ "x".repeat(101), "식당", "37.55", "127.05" },
				{ "p1", "가".repeat(101), "37.55", "127.05" },
				{ "p1", "식당", "91", "127.05" },
				{ "p1", "식당", "37.55", "-181" },
				{ "p1", "식당", "NaN", "127.05" },
				{ "p1", "식당", "37.55", "Infinity" },
				{ "p1", "식당", "north", "127.05" },
		};
		for (String[] params : bad) {
			mvc.perform(get("/api/places/course")
							.param("id", params[0]).param("name", params[1]).param("lat", params[2]).param("lng", params[3]))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(get("/api/places/course").param("id", "p1").param("name", "식당").param("lng", "127.05"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(places);
	}
```

- [ ] **Step 7: Run them to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.place.PlaceControllerTest'`
Expected: FAIL — `pickedCourseNeedsNoLogin` gets 401 (the path needs login and has no handler yet); `pickedCourseChecksItsInput` gets 401 instead of 400.

- [ ] **Step 8: Add the endpoint and open it**

`PlaceController.java`에 import `com.example.pickyourfood.place.PlacesResponse.Course;`를 더하고, `mapKey()` 위에:

```java
	// the client sends the restaurant it already has: Kakao has no lookup by id
	@GetMapping("/api/places/course")
	Course course(@RequestParam String id, @RequestParam String name, @RequestParam double lat, @RequestParam double lng) {
		// written as "not within" so NaN, which fails every comparison, is rejected too
		if (!fits(id) || !fits(name) || !(Math.abs(lat) <= 90) || !(Math.abs(lng) <= 180)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id, name, lat or lng is invalid");
		}
		return places.course(id.strip(), name.strip(), lat, lng);
	}

	private static boolean fits(String value) {
		return !value.isBlank() && value.strip().length() <= 100;
	}
```

`SecurityConfig.java` 19번째 줄의 목록에 `"/api/places/course"`를 더한다:

```java
						.requestMatchers("/api/foods/**", "/api/recommendations", "/api/places", "/api/places/course", "/api/places/map-key").permitAll()
```

- [ ] **Step 9: Run the place tests**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.place.*'`
Expected: PASS.

- [ ] **Step 10: Run the whole suite and commit**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test`
Expected: PASS (all tests).

```bash
git add src/main/java/com/example/pickyourfood/place src/main/java/com/example/pickyourfood/account/SecurityConfig.java src/test/java/com/example/pickyourfood/place
git commit -m "feat: build a date course from a picked restaurant

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 저장 결과에 내 코스 남기기

**Files:**
- Modify: `src/main/java/com/example/pickyourfood/saved/SavedResult.java`
- Modify: `src/main/java/com/example/pickyourfood/saved/SavedController.java:74-79`
- Test: `src/test/java/com/example/pickyourfood/saved/SavedControllerTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: 저장 JSON의 코스에 선택 필드 `"picked": true`. 없거나 `null`이면 자동 코스. 코스 최대 4개, `picked: true` 최대 1개.

- [ ] **Step 1: Write the failing tests**

`SavedControllerTest.java`에서:

1. `malformedOrOversizedResultsAreRejected`의 `rejects(withPlaces("", "", repeat(course, 4)));`를 `rejects(withPlaces("", "", repeat(course, 5)));`로 바꾼다.
2. `savedResultGetsAnUnguessableLinkAndDropsGoogleAndUnknownFields`의 `.andExpect(jsonPath("$.result.places.dateCourses[0].legs[0].path").isEmpty())` 아래에 한 줄을 더한다:

```java
				.andExpect(jsonPath("$.result.places.dateCourses[0].picked").isEmpty())
```

3. `withLegs` 헬퍼 아래에 새 테스트:

```java
	@Test
	void pickedCourseIsKeptAndAtMostOneOfFourCourses() throws Exception {
		String place = "{\"id\":\"p\",\"name\":\"식당\",\"address\":\"\",\"distanceMeters\":1,\"lat\":0,\"lng\":0,\"kakaoUrl\":\"\"}";
		String course = "{\"restaurant\":" + place + ",\"cafe\":null,\"sight\":null,\"legs\":[],\"routeUrl\":null}";
		String picked = "{\"restaurant\":" + place + ",\"cafe\":null,\"sight\":null,\"legs\":[],\"routeUrl\":null,\"picked\":true}";
		String id = save(SEOYUN, withPlaces("", "", picked + "," + repeat(course, 3)));

		mvc.perform(get("/api/saved/" + id))
				.andExpect(jsonPath("$.result.places.dateCourses", hasSize(4)))
				.andExpect(jsonPath("$.result.places.dateCourses[0].picked").value(true))
				.andExpect(jsonPath("$.result.places.dateCourses[1].picked").isEmpty());

		rejects(withPlaces("", "", picked + "," + repeat(course, 4)));
		rejects(withPlaces("", "", picked + "," + picked));
	}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.saved.SavedControllerTest'`
Expected: FAIL — `pickedCourseIsKeptAndAtMostOneOfFourCourses` gets 400 on the 4-course save (limit is 3) and `picked` is dropped as an unknown field.

- [ ] **Step 3: Keep `picked` and raise the limit**

`SavedResult.java`의 `DateCourse`를 바꾼다:

```java
	// picked is true for the course built from a restaurant the user chose; null for the others and older results
	record DateCourse(Place restaurant, Spot cafe, Spot sight, List<Leg> legs, String routeUrl, Boolean picked) {
	}
```

`SavedController.valid(SavedResult.Places p)`를 바꾼다:

```java
	// up to three courses from the search and one the user picked
	private static boolean valid(SavedResult.Places p) {
		return p.origin() != null && p.origin().name() != null && p.origin().name().length() <= 200
				&& fits(p.nearby(), 10) && fits(p.famous(), 10) && fits(p.dateCourses(), 4)
				&& p.dateCourses().stream().filter(c -> Boolean.TRUE.equals(c.picked())).count() <= 1
				&& p.dateCourses().stream().allMatch(c -> c.restaurant() != null && fits(c.legs(), 2)
						&& c.legs().stream().allMatch(leg -> leg.path() == null || validPath(leg.path())));
	}
```

`fits(p.dateCourses(), 4)`가 먼저 `null`을 걸러 내므로 뒤의 두 stream은 안전하다.

- [ ] **Step 4: Run the saved tests, then the whole suite**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.saved.*' && ./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pickyourfood/saved src/test/java/com/example/pickyourfood/saved
git commit -m "feat: keep the picked course in saved results

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 표에서 식당을 골라 코스 만들기 (화면)

**Files:**
- Modify: `frontend/src/shared/places/types.ts`
- Modify: `frontend/src/shared/places/PlaceTable.tsx`
- Modify: `frontend/src/shared/places/DateCourses.tsx`
- Modify: `frontend/src/features/places/api.ts`
- Modify: `frontend/src/features/places/PlacesSection.tsx`

**Interfaces:**
- Consumes: Task 1의 `GET /api/places/course?id=&name=&lat=&lng=` → `{cafe, sight, legs, routeUrl}`. Task 2의 저장 필드 `picked`.
- Produces:
  - `DateCourse.picked?: boolean | null`.
  - `PlaceTable`의 선택 prop `course?: CoursePicker`, `export type CoursePicker = { pick: (place: Place) => void; pending: string | null; failed: string | null }`.
  - `DateCourses`의 선택 prop `initial?: number`.
  - `fetchCourse(place: Place): Promise<DateCourse>`.

- [ ] **Step 1: Mark picked courses in the type**

`types.ts`의 `DateCourse`를 바꾼다:

```ts
// legs join only the stops that exist; routeUrl is null when the restaurant is the only stop;
// picked is true for the course built from a restaurant the user chose (missing or null for the others)
export type DateCourse = {
  restaurant: Place
  cafe: Spot | null
  sight: Spot | null
  legs: Leg[]
  routeUrl: string | null
  picked?: boolean | null
}
```

- [ ] **Step 2: Add the button to the table**

`PlaceTable.tsx`:

1. 첫 줄 import를 `import { ArrowDown, ArrowUp, Path } from '@phosphor-icons/react'`로 바꾼다.
2. `type Props` 위에:

```ts
// lets a search build a date course from a row (values are place ids); the saved view leaves it out
export type CoursePicker = { pick: (place: Place) => void; pending: string | null; failed: string | null }
```

3. `Props`에 `course?: CoursePicker`를 더하고, `PlaceTable({ food, nearby, famous, course }: Props)`로 받아 `<PlaceRow … course={course} />`로 넘긴다.
4. `PlaceRowProps`에 `course?: CoursePicker`를 더하고 `PlaceRow({ place, open, onToggle, course }: PlaceRowProps)`로 받아 `<Reviews place={place} course={course} />`로 넘긴다.
5. `Reviews`를 `function Reviews({ place, course }: { place: Place; course?: CoursePicker })`로 바꾸고, 링크 묶음 `div`를 이렇게 바꾼다:

```tsx
      <div className="flex flex-wrap gap-2 md:flex-col">
        <MapLink href={place.kakaoUrl} label="카카오맵" />
        {place.googleUrl && <MapLink href={place.googleUrl} label="Google 지도" />}
        {course && (
          <button
            type="button"
            onClick={() => course.pick(place)}
            disabled={course.pending === place.id}
            className="flex w-fit items-center gap-2 rounded-full border border-accent px-4 py-2 text-sm font-medium text-accent transition active:scale-[0.98] disabled:opacity-40"
          >
            <Path size={16} /> {course.pending === place.id ? '코스 만드는 중…' : '이 식당으로 코스 만들기'}
          </button>
        )}
        {course?.failed === place.id && (
          <p role="alert" className="text-sm text-accent">
            코스를 만들지 못했어요. 다시 시도해 주세요.
          </p>
        )}
      </div>
```

- [ ] **Step 3: Show `내 코스` and start on a chosen course**

`DateCourses.tsx`:

1. 컴포넌트 위 주석과 시그니처를 바꾼다:

```tsx
// starts on the initial course (the first by default); PlacesSection remounts this for every new search and picked course
export default function DateCourses({ courses, initial = 0 }: { courses: DateCourse[]; initial?: number }) {
  const [selected, setSelected] = useState(initial)
  const { restaurant, cafe, sight, legs, routeUrl, picked } = courses[selected]
```

2. 라벨 줄을 바꾼다:

```tsx
      <p className="text-sm font-medium text-accent">{picked ? '내 데이트 코스' : '데이트 코스'}</p>
```

3. 탭 안의 이름 `span`을 바꾼다(번호와 식당 이름 사이에 `내 코스`):

```tsx
              <span className={`relative block truncate ${index === selected ? 'text-white' : 'text-zinc-600'}`}>
                <span className="font-mono">{number(index)}</span>{' '}
                {course.picked && <span className={index === selected ? 'text-accent-soft' : 'text-accent'}>내 코스 </span>}
                {course.restaurant.name}
              </span>
```

- [ ] **Step 4: Request a picked course**

`features/places/api.ts`를 바꾼다:

```ts
import { request } from '../../shared/api.ts'
import type { DateCourse, Place, Places } from '../../shared/places/types.ts'

export type Where = { lat: number; lng: number } | { near: string }

export function fetchPlaces(food: string, where: Where): Promise<Places> {
  const params = new URLSearchParams(
    'near' in where ? { food, near: where.near } : { food, lat: String(where.lat), lng: String(where.lng) },
  )
  return request(`/api/places?${params}`)
}

// the server answers the course without its restaurant; the place on screen fills it in
export async function fetchCourse(place: Place): Promise<DateCourse> {
  const params = new URLSearchParams({ id: place.id, name: place.name, lat: String(place.lat), lng: String(place.lng) })
  const course = await request<Omit<DateCourse, 'restaurant' | 'picked'>>(`/api/places/course?${params}`)
  return { restaurant: place, ...course, picked: true }
}
```

- [ ] **Step 5: Wire it in `PlacesSection`**

`PlacesSection.tsx`:

1. import를 바꾼다:

```tsx
import { Crosshair, MapPin } from '@phosphor-icons/react'
import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { HttpError } from '../../shared/api.ts'
import DateCourses from '../../shared/places/DateCourses.tsx'
import PlaceTable from '../../shared/places/PlaceTable.tsx'
import type { Place, Places } from '../../shared/places/types.ts'
import { fetchCourse, fetchPlaces } from './api.ts'
import type { Where } from './api.ts'
```

2. `const lastWhere = useRef<Where | null>(null)` 아래에:

```tsx
  // the picked-course request in flight or the one that failed, by place id
  const [picking, setPicking] = useState<{ pending: string | null; failed: string | null }>({ pending: null, failed: null })
  // which course the course section starts on; a new version remounts it there and scrolls to it
  const [shown, setShown] = useState({ index: 0, version: 0 })
  const latestCourse = useRef(0)
  const coursesRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (shown.version > 0) coursesRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [shown.version])
```

3. `search` 함수 맨 앞(`const id = ++latest.current` 다음)에 코스 버튼 상태와 처음 선택할 코스를 되돌리는 줄을 더한다(진행 중인 코스 응답은 `pick`이 `latest`로 버린다):

```tsx
    setPicking({ pending: null, failed: null })
    setShown((prev) => ({ ...prev, index: 0 }))
```

4. `search` 아래에 새 함수:

```tsx
  function show(index: number) {
    setShown((prev) => ({ index, version: prev.version + 1 }))
  }

  // one picked course at a time: a new one replaces the last, and a restaurant that already has a course just shows it
  function pick(place: Place) {
    if (state.status !== 'done') return
    const data = state.data
    const existing = data.dateCourses.findIndex((course) => course.restaurant.id === place.id)
    if (existing >= 0) {
      show(existing)
      return
    }
    const id = ++latestCourse.current
    const searchId = latest.current
    // only the last click counts, and only while its search is still on screen (a new search or locate bumps latest)
    const current = () => id === latestCourse.current && searchId === latest.current
    setPicking({ pending: place.id, failed: null })
    fetchCourse(place).then(
      (course) => {
        if (!current()) return
        const next = { ...data, dateCourses: [course, ...data.dateCourses.filter((other) => !other.picked)] }
        setState({ status: 'done', data: next })
        onLoaded(next)
        setPicking({ pending: null, failed: null })
        show(0)
      },
      () => current() && setPicking({ pending: null, failed: place.id }),
    )
  }
```

(`data`는 누른 순간의 결과다. 그 뒤 다른 식당 선택이나 새 검색·현재 위치 찾기가 있으면 `latestCourse`나 `latest`가 바뀌어 이 응답은 버려지므로, 응답이 반영될 때 `data`는 항상 화면의 결과와 같다.)

5. `done` 화면의 표와 코스 부분을 바꾼다:

```tsx
            <div className="mt-6">
              <PlaceTable
                food={food}
                nearby={state.data.nearby}
                famous={state.data.famous}
                course={{ pick, ...picking }}
              />
            </div>
            {state.data.dateCourses.length > 0 && (
              <div ref={coursesRef} className="mt-16 scroll-mt-8">
                <DateCourses key={shown.version} courses={state.data.dateCourses} initial={shown.index} />
              </div>
            )}
```

- [ ] **Step 6: Build and lint**

Run: `cd frontend && npm run build && npm run lint`
Expected: both exit 0, no type or lint errors.

- [ ] **Step 7: Headless browser check**

Throwaway 확인 스크립트와 스크린샷은 저장소 밖(세션 scratchpad)에 둔다. 참고용 이전 드라이버: `/private/tmp/claude-501/-Users-limdongju-Downloads-pick-your-food/7c51f93e-280b-454f-9c0c-6d23eb296a83/scratchpad/g2-ui/cdp.mjs`(CDP `Fetch` 도메인으로 `/api/*`와 `dapi.kakao.com`을 가로채 준비한 응답을 돌려준다). Vite는 `cd frontend && npx vite --port 5174 --strictPort`, Chrome은 `--headless=new --remote-debugging-port=9222`. 끝나면 둘 다 `pkill`.

가로챌 응답: `/api/me` 401, 메뉴 화면까지 가는 데 필요한 `/api/foods/*`, `/api/places/map-key` `{"key":null}`(지도 숨김, 확인 대상 아님), `/api/places`는 nearby 2곳(`n1`,`n2`)·famous 1곳(`f1`)·`dateCourses`에 `f1` 코스 1개, `/api/places/course`는 `id`별로 준비한 코스(카페 하나, 구간 하나, `routeUrl` 있음). 요청 URL을 모두 기록한다.

확인 항목:
1. `n1` 행을 펼쳐 `이 식당으로 코스 만들기` → (응답을 1초 늦춰) 버튼이 `코스 만드는 중…`이고 비활성 → 코스 탭이 `01 내 코스 n1`, `02 f1` 순서, 첫 탭 선택, 라벨 `내 데이트 코스`, 코스 영역이 화면에 보이도록 스크롤됨.
2. `n2`로 다시 만들면 탭이 `01 내 코스 n2`, `02 f1`(탭 수 2 그대로).
3. 근교 탭에서 `f1`의 버튼 → `/api/places/course` 요청 없이 `f1` 탭이 선택되고 라벨 `데이트 코스`.
4. `/api/places/course`가 500 → `n1` 행 안에 `코스를 만들지 못했어요. 다시 시도해 주세요.`, 다시 누르면(이번엔 200) 성공.
5. `dateCourses: []`인 검색 → 코스 영역 없음 → `n1`으로 만들면 코스 영역이 나타나고 탭은 숨김(코스 1개).
6. 저장 결과 화면(`/s/test`, `/api/saved/test`에 `picked: true` 코스 하나와 자동 코스 하나)의 표에는 버튼이 없고, 탭에 `내 코스`가 보인다.
7. `n1` 요청(2초 지연) 중에 `n2`(즉시)를 누르면 최종 탭은 `내 코스 n2`이고, 늦게 끝난 `n1` 응답이 덮어쓰지 않는다.
8. `n1` 요청(2초 지연) 중에 다른 동네로 새 검색 → 새 결과에 `내 코스`가 끼어들지 않는다. 같은 지연 중 `현재 위치`(위치 찾는 중 상태)를 눌러도 늦게 온 응답이 화면을 결과로 되돌리지 않는다.
9. 390px 폭에서 펼친 행의 버튼이 줄바꿈되어 가로 스크롤이 생기지 않는다(스크린샷).

- [ ] **Step 8: Commit**

```bash
git add frontend/src/shared/places frontend/src/features/places
git commit -m "feat: build a date course from a restaurant picked in the table

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
