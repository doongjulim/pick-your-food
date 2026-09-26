# Date Courses (Sub-project C1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn B's single date course into up to three courses (one per top famous place) with walking legs between stops and a Kakao Map walking-route link.

**Architecture:** `KakaoClient.nearby` returns the 3 nearest category hits; `PlaceService` looks up cafe/sight candidates for the top 3 famous places in parallel, picks spots in course order skipping ones an earlier course used, and computes straight-line legs and a route URL. The frontend `DateCourses` component shows course tabs, leg labels and the route button.

**Tech Stack:** Spring Boot 4.1.1 (`RestClient`), JUnit 5 + `MockRestServiceServer` + Mockito + MockMvc; React 19 + TypeScript 6 + Tailwind v4 + `motion` + `@phosphor-icons/react`.

**Spec:** `docs/superpowers/specs/2026-09-26-date-courses-design.md`

## Global Constraints

- Every Gradle command runs with `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` (default JDK is 11).
- Changes stay inside backend `com.example.pickyourfood.place` and frontend `frontend/src/features/places/` (package by feature, CLAUDE.md).
- No new dependencies, no new API keys.
- Courses: top ≤3 of the sorted famous list; cafe `CE7` ≤ 500m, sight `AT4` ≤ 1,000m, `sort=distance`, `size=3`.
- Walking: haversine meters (`PlaceService.meters`), `walkMinutes = max(1, ceil(meters / 67))`.
- Route: `https://map.kakao.com/link/by/walk/{name},{lat},{lng}/…`, names percent-encoded after `,` and `/` become spaces; `null` when the restaurant is the only stop.
- A failed cafe/sight search (any exception) logs a warning and leaves that step `null`; list-search failures stay 502.
- API field `dateCourse` is replaced by `dateCourses` (0–3 entries); no compatibility field.
- Copy: `도보 5분 · 320m`, `식당에서 도보 12분 · 780m`, `전체 경로 보기`, `직선거리 기준 예상 시간이에요`, `{식당}에서 시작해요`.
- Design: zinc base + single `accent`, Phosphor icons, no emojis, spring `{ type: 'spring', stiffness: 100, damping: 20 }`, no horizontal scroll at 375px.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never commit `.claude/`, `.serena/`, `graphify-out/`.

## Review Focus

1. Place names with `,` `/` spaces or Hangul in the route link → encoded, never breaking the link's `name,lat,lng/` structure. Pinned by `PlaceServiceTest.routeUrlListsStopsInOrderWithEncodedNames` (Task 1).
2. One Kakao cafe/sight search failing (502, timeout) → the request still succeeds and only that step is empty. Pinned by `PlaceServiceTest.failedCafeSearchLeavesOnlyThatStepEmpty` (Task 1).
3. Two famous places close together share the same nearest cafe → the later course takes its next candidate, or the shared one when nothing else exists. Pinned by `laterCourseSkipsACafeAnEarlierCourseUses` and `everyCandidateTakenFallsBackToTheNearest` (Task 1).
4. A stop at the same coordinate (0m) → `도보 1분`, never `도보 0분`. Pinned by `withoutACafeTheSightIsReachedFromTheRestaurant` (Task 1).
5. A new search after picking course 2 or 3 → selection back on course 1 (the component remounts because the section passes through `loading`), and long restaurant names in tabs don't cause horizontal scroll at 375px. Pinned by the `courses` and `mobile` browser scenarios (Task 2).

---

### Task 1: Backend — multiple courses with legs and route

**Files:**
- Modify: `src/main/java/com/example/pickyourfood/place/KakaoClient.java` (`nearest` → `nearby`)
- Modify: `src/main/java/com/example/pickyourfood/place/PlacesResponse.java`
- Modify: `src/main/java/com/example/pickyourfood/place/PlaceService.java`
- Test: `src/test/java/com/example/pickyourfood/place/KakaoClientTest.java`
- Test: `src/test/java/com/example/pickyourfood/place/PlaceServiceTest.java`
- Test: `src/test/java/com/example/pickyourfood/place/PlaceControllerTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces (JSON consumed by Task 2):
  - `PlacesResponse(Origin origin, List<Place> nearby, List<Place> famous, List<DateCourse> dateCourses)`
  - `Spot(String id, String name, String category, String address, double lat, double lng, String kakaoUrl)`
  - `Leg(String from, String to, int meters, int walkMinutes)`
  - `DateCourse(Place restaurant, Spot cafe, Spot sight, List<Leg> legs, String routeUrl)` — `cafe`/`sight`/`routeUrl` nullable.
  - `KakaoClient.nearby(String category, double lat, double lng, int radius): List<KakaoPlace>` (nearest first, at most 3).

- [ ] **Step 1: Update the Kakao client test**

In `KakaoClientTest.java`, replace the whole `nearestSendsCategorySearchSortedByDistance` test with:

```java
	@Test
	void nearbySendsCategorySearchAndReturnsTheNearestThree() {
		server.expect(requestTo(containsString("https://dapi.kakao.com/v2/local/search/category.json")))
				.andExpect(queryParam("category_group_code", "CE7"))
				.andExpect(queryParam("x", "127.05"))
				.andExpect(queryParam("y", "37.54"))
				.andExpect(queryParam("radius", "500"))
				.andExpect(queryParam("sort", "distance"))
				.andExpect(queryParam("size", "3"))
				.andRespond(withSuccess("""
						{"documents":[
						{"id":"1","place_name":"어니언","category_name":"음식점 > 카페","address_name":"","road_address_name":"서울 성동구 1",
						"x":"127.051","y":"37.541","distance":"120","place_url":"http://place.map.kakao.com/1"},
						{"id":"2","place_name":"대림창고","category_name":"음식점 > 카페","address_name":"","road_address_name":"서울 성동구 2",
						"x":"127.052","y":"37.542","distance":"260","place_url":"http://place.map.kakao.com/2"}]}""",
						MediaType.APPLICATION_JSON));

		assertThat(kakao.nearby(KakaoClient.CAFE, 37.54, 127.05, 500)).extracting(KakaoPlace::id).containsExactly("1", "2");
		server.verify();
	}
```

- [ ] **Step 2: Update the service tests**

In `PlaceServiceTest.java`:

Add imports:

```java
import com.example.pickyourfood.place.PlacesResponse.DateCourse;
import com.example.pickyourfood.place.PlacesResponse.Leg;
```

Add these helpers after `googleKnows`:

```java
	// latitudes of the famous places in rank order, all at longitude 127.05
	private static final double[] RANKED_LATS = { 37.56, 37.57, 37.58, 37.59 };

	// famous places ranked in the given order: the first has the most reviews
	private void famousRanked(String... ids) {
		KakaoPlace[] places = new KakaoPlace[ids.length];
		for (int i = 0; i < ids.length; i++) {
			places[i] = place(ids[i], RANKED_LATS[i]);
			googleKnows(ids[i], info(RANKED_LATS[i], 4.0, 1000 - i * 100));
		}
		famousCandidatesAre(places);
	}

	private static KakaoPlace spot(String id, double lat) {
		return new KakaoPlace(id, id, "카페", "주소 " + id, lat, 127.05, 0, "https://place.map.kakao.com/" + id);
	}

	private void cafesNear(double lat, KakaoPlace... found) {
		when(kakao.nearby(KakaoClient.CAFE, lat, 127.05, PlaceService.CAFE_RADIUS)).thenReturn(List.of(found));
	}

	private void sightsNear(double lat, KakaoPlace... found) {
		when(kakao.nearby(KakaoClient.SIGHT, lat, 127.05, PlaceService.SIGHT_RADIUS)).thenReturn(List.of(found));
	}
```

Replace the two tests `dateCourseStartsAtTheTopFamousPlace` and `noFamousPlacesMeansNoDateCourse` with:

```java
	@Test
	void threeCoursesStartAtTheTopThreeFamousPlaces() {
		famousRanked("r1", "r2", "r3", "r4");

		List<DateCourse> courses = service.search("ramen", ORIGIN).dateCourses();

		assertThat(courses).extracting(course -> course.restaurant().id()).containsExactly("r1", "r2", "r3");
		verify(kakao).nearby(KakaoClient.CAFE, 37.58, 127.05, PlaceService.CAFE_RADIUS);
		verify(kakao).nearby(KakaoClient.SIGHT, 37.58, 127.05, PlaceService.SIGHT_RADIUS);
		verify(kakao, never()).nearby(anyString(), eq(37.59), anyDouble(), anyInt());
	}

	@Test
	void twoFamousPlacesMakeTwoCourses() {
		famousRanked("r1", "r2");

		assertThat(service.search("ramen", ORIGIN).dateCourses()).hasSize(2);
	}

	@Test
	void noFamousPlacesMeansNoCourses() {
		PlacesResponse response = service.search("ramen", ORIGIN);

		assertThat(response.famous()).isEmpty();
		assertThat(response.dateCourses()).isEmpty();
		verify(kakao, never()).nearby(anyString(), anyDouble(), anyDouble(), anyInt());
	}

	@Test
	void laterCourseSkipsACafeAnEarlierCourseUses() {
		famousRanked("r1", "r2");
		cafesNear(37.56, spot("shared", 37.561));
		cafesNear(37.57, spot("shared", 37.561), spot("own", 37.572));

		List<DateCourse> courses = service.search("ramen", ORIGIN).dateCourses();

		assertThat(courses).extracting(course -> course.cafe().id()).containsExactly("shared", "own");
	}

	@Test
	void everyCandidateTakenFallsBackToTheNearest() {
		famousRanked("r1", "r2");
		cafesNear(37.56, spot("shared", 37.565));
		cafesNear(37.57, spot("shared", 37.565));

		List<DateCourse> courses = service.search("ramen", ORIGIN).dateCourses();

		assertThat(courses).extracting(course -> course.cafe().id()).containsExactly("shared", "shared");
	}

	@Test
	void legsMeasureWalkingDistanceRoundedUpToAMinute() {
		famousRanked("r1");
		cafesNear(37.56, spot("cafe", 37.564)); // 445m from the restaurant
		sightsNear(37.56, spot("sight", 37.569)); // 556m from the cafe

		DateCourse course = service.search("ramen", ORIGIN).dateCourses().get(0);

		assertThat(course.legs()).containsExactly(new Leg("r1", "cafe", 445, 7), new Leg("cafe", "sight", 556, 9));
	}

	@Test
	void withoutACafeTheSightIsReachedFromTheRestaurant() {
		famousRanked("r1");
		sightsNear(37.56, spot("sight", 37.56)); // same spot: 0m still takes a minute

		DateCourse course = service.search("ramen", ORIGIN).dateCourses().get(0);

		assertThat(course.cafe()).isNull();
		assertThat(course.legs()).containsExactly(new Leg("r1", "sight", 0, 1));
		assertThat(course.routeUrl()).isNotNull();
	}

	@Test
	void restaurantOnlyCourseHasNoLegsOrRoute() {
		famousRanked("r1");

		DateCourse course = service.search("ramen", ORIGIN).dateCourses().get(0);

		assertThat(course.cafe()).isNull();
		assertThat(course.sight()).isNull();
		assertThat(course.legs()).isEmpty();
		assertThat(course.routeUrl()).isNull();
	}

	@Test
	void failedCafeSearchLeavesOnlyThatStepEmpty() {
		famousRanked("r1");
		when(kakao.nearby(KakaoClient.CAFE, 37.56, 127.05, PlaceService.CAFE_RADIUS))
				.thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
		sightsNear(37.56, spot("sight", 37.569));

		DateCourse course = service.search("ramen", ORIGIN).dateCourses().get(0);

		assertThat(course.cafe()).isNull();
		assertThat(course.sight().id()).isEqualTo("sight");
	}

	@Test
	void routeUrlListsStopsInOrderWithEncodedNames() {
		famousRanked("탄탄 집");
		cafesNear(37.56, spot("a,b/c", 37.564));
		sightsNear(37.56, spot("서울숲", 37.569));

		DateCourse course = service.search("ramen", ORIGIN).dateCourses().get(0);

		assertThat(course.routeUrl()).isEqualTo("https://map.kakao.com/link/by/walk/"
				+ "%ED%83%84%ED%83%84%20%EC%A7%91,37.56,127.05/"
				+ "a%20b%20c,37.564,127.05/"
				+ "%EC%84%9C%EC%9A%B8%EC%88%B2,37.569,127.05");
	}
```

- [ ] **Step 3: Update the controller test**

In `PlaceControllerTest.java`, change `empty` to:

```java
	private static PlacesResponse empty(Origin origin) {
		return new PlacesResponse(origin, List.of(), List.of(), List.of());
	}
```

and in `coordinatesSearchFromTheCurrentLocation` add after `.andExpect(jsonPath("$.nearby").isArray())`:

```java
				.andExpect(jsonPath("$.dateCourses").isArray())
```

(remove the `;` from the `nearby` line so the chain continues).

- [ ] **Step 4: Run the tests to see them fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.place.*' -q 2>&1 | grep -E 'error:|FAILED' | head`
Expected: test compilation fails — `cannot find symbol` for `nearby(...)`, `dateCourses()`, `Leg`, and the `PlacesResponse` constructor.

- [ ] **Step 5: Replace `nearest` with `nearby` in `KakaoClient`**

Replace the whole `nearest` method with:

```java
	// the three nearest places of a category, nearest first
	public List<KakaoPlace> nearby(String category, double lat, double lng, int radius) {
		return search(uri -> uri.path("/v2/local/search/category.json")
				.queryParam("category_group_code", category).queryParam("x", lng).queryParam("y", lat)
				.queryParam("radius", radius).queryParam("sort", "distance").queryParam("size", 3)
				.build());
	}
```

Remove the `java.util.Optional` import only if nothing else in the file still uses it (`locate` does, so it stays).

- [ ] **Step 6: Change the response records**

In `PlacesResponse.java`, change the header and replace `Spot` and `DateCourse`:

```java
public record PlacesResponse(Origin origin, List<Place> nearby, List<Place> famous, List<DateCourse> dateCourses) {
```

```java
	public record Spot(String id, String name, String category, String address, double lat, double lng, String kakaoUrl) {
	}

	// straight-line distance between two stops; walkMinutes rounds up and is at least 1
	public record Leg(String from, String to, int meters, int walkMinutes) {
	}

	// cafe and sight are null when nothing is found near the restaurant; legs join only the stops that exist,
	// and routeUrl (a Kakao Map walking route through every stop) is null when the restaurant is the only stop
	public record DateCourse(Place restaurant, Spot cafe, Spot sight, List<Leg> legs, String routeUrl) {
	}
```

- [ ] **Step 7: Build the courses in `PlaceService`**

Imports — add:

```java
import com.example.pickyourfood.place.PlacesResponse.Leg;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.stream.IntStream;
```

Constants — add after `SIGHT_RADIUS`:

```java
	static final int COURSES = 3;
	// about 4km/h
	static final int WALK_METERS_PER_MINUTE = 67;
	static final String ROUTE_BASE = "https://map.kakao.com/link/by/walk/";
```

Rename the pool's thread from `"google-places"` to `"place-lookups"` (it now runs Kakao course lookups too).

In `search`, change the last line to:

```java
		return new PlacesResponse(origin, nearby, famous, dateCourses(famous));
```

Replace the `dateCourse` and `spot` methods with:

```java
	private List<DateCourse> dateCourses(List<Place> famous) {
		List<Place> restaurants = famous.stream().limit(COURSES).toList();
		// start every Kakao lookup before waiting on any of them
		List<CompletableFuture<List<KakaoPlace>>> cafes = restaurants.stream()
				.map(restaurant -> candidatesAsync(KakaoClient.CAFE, restaurant, CAFE_RADIUS)).toList();
		List<CompletableFuture<List<KakaoPlace>>> sights = restaurants.stream()
				.map(restaurant -> candidatesAsync(KakaoClient.SIGHT, restaurant, SIGHT_RADIUS)).toList();
		// picked in course order, so an earlier course keeps its nearest spot
		Set<String> used = new HashSet<>();
		List<DateCourse> courses = new ArrayList<>();
		for (int i = 0; i < restaurants.size(); i++) {
			Spot cafe = pick(cafes.get(i).join(), used);
			Spot sight = pick(sights.get(i).join(), used);
			courses.add(course(restaurants.get(i), cafe, sight));
		}
		return courses;
	}

	private CompletableFuture<List<KakaoPlace>> candidatesAsync(String category, Place from, int radius) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return kakao.nearby(category, from.lat(), from.lng(), radius);
			} catch (RuntimeException e) {
				// the course just leaves this step empty
				log.warn("Kakao {} search failed near {}: {}", category, from.name(), e.getMessage());
				return List.of();
			}
		}, pool);
	}

	// nearest candidate no earlier course uses; when every one is taken, the nearest anyway
	private static Spot pick(List<KakaoPlace> candidates, Set<String> used) {
		return candidates.stream().filter(found -> !used.contains(found.id())).findFirst()
				.or(() -> candidates.stream().findFirst())
				.map(found -> {
					used.add(found.id());
					return new Spot(found.id(), found.name(), found.category(), found.address(), found.lat(), found.lng(), found.url());
				})
				.orElse(null);
	}

	private static DateCourse course(Place restaurant, Spot cafe, Spot sight) {
		List<Stop> stops = new ArrayList<>();
		stops.add(new Stop(restaurant.name(), restaurant.lat(), restaurant.lng()));
		if (cafe != null) stops.add(new Stop(cafe.name(), cafe.lat(), cafe.lng()));
		if (sight != null) stops.add(new Stop(sight.name(), sight.lat(), sight.lng()));
		List<Leg> legs = IntStream.range(1, stops.size()).mapToObj(i -> leg(stops.get(i - 1), stops.get(i))).toList();
		String routeUrl = legs.isEmpty() ? null
				: ROUTE_BASE + stops.stream()
						.map(stop -> routeName(stop.name()) + "," + stop.lat() + "," + stop.lng())
						.collect(Collectors.joining("/"));
		return new DateCourse(restaurant, cafe, sight, legs, routeUrl);
	}

	private static Leg leg(Stop from, Stop to) {
		int meters = meters(from.lat(), from.lng(), to.lat(), to.lng());
		int minutes = Math.max(1, (int) Math.ceil(meters / (double) WALK_METERS_PER_MINUTE));
		return new Leg(from.name(), to.name(), meters, minutes);
	}

	// ',' and '/' separate the link's parts, so names lose them before encoding
	private static String routeName(String name) {
		return URLEncoder.encode(name.replace(',', ' ').replace('/', ' '), StandardCharsets.UTF_8).replace("+", "%20");
	}
```

Add next to the `Cached` record:

```java
	private record Stop(String name, double lat, double lng) {
	}
```

- [ ] **Step 8: Run the place tests**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.place.*' --rerun-tasks -q`
Expected: no output (all place tests pass).

- [ ] **Step 9: Run the whole suite**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --rerun-tasks -q`
Expected: no failures; the suite is 42 − 2 + 10 = 50 tests (count from `build/test-results/test/*.xml`).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/example/pickyourfood/place src/test/java/com/example/pickyourfood/place
git commit -m "feat: build up to three date courses with walking legs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Frontend — course tabs, legs and route button

**Files:**
- Modify: `frontend/src/features/places/api.ts`
- Delete: `frontend/src/features/places/DateCourse.tsx`
- Create: `frontend/src/features/places/DateCourses.tsx`
- Modify: `frontend/src/features/places/PlacesSection.tsx` (import on line 7, course block at lines ~134–138)
- Check script (workspace, not committed): `<workspace>/courses-check.mjs`

**Interfaces:**
- Consumes: Task 1's JSON — `dateCourses: [{ restaurant, cafe, sight, legs: [{ from, to, meters, walkMinutes }], routeUrl }]`.
- Produces: `DateCourses({ courses }: { courses: DateCourse[] })` default export.

- [ ] **Step 1: Update the types**

In `api.ts`, replace `Spot`, `DateCourse` and the `dateCourse` field:

```ts
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
```

```ts
  dateCourses: DateCourse[]
```

- [ ] **Step 2: Run the type check to see it fail**

Run: `cd frontend && npm run build 2>&1 | grep -E 'error TS' | head`
Expected: errors in `DateCourse.tsx` (`distanceMeters` does not exist on `Spot`) and `PlacesSection.tsx` (`dateCourse` does not exist on `Places`).

- [ ] **Step 3: Replace `DateCourse.tsx` with `DateCourses.tsx`**

Run: `git rm -q frontend/src/features/places/DateCourse.tsx`

Create `frontend/src/features/places/DateCourses.tsx`:

```tsx
import { Coffee, ForkKnife, MapTrifold, Mountains, PersonSimpleWalk } from '@phosphor-icons/react'
import type { Icon } from '@phosphor-icons/react'
import { AnimatePresence, motion } from 'motion/react'
import { useState } from 'react'
import type { DateCourse, Leg } from './api.ts'
import { distance } from './format.ts'
import MapLink from './MapLink.tsx'

// leg is the walk into this step, shown above it
type Step = { icon: Icon; label: string; leg: string | null } & (
  | { name: string; detail: string; url: string }
  | { missing: string }
)

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

const number = (index: number) => String(index + 1).padStart(2, '0')

function walk(leg: Leg, fromRestaurant: boolean): string {
  return `${fromRestaurant ? '식당에서 ' : ''}도보 ${leg.walkMinutes}분 · ${distance(leg.meters)}`
}

// starts on the first course; PlacesSection remounts this for every new search
export default function DateCourses({ courses }: { courses: DateCourse[] }) {
  const [selected, setSelected] = useState(0)
  const { restaurant, cafe, sight, legs, routeUrl } = courses[selected]
  const steps: Step[] = [
    { icon: ForkKnife, label: '식사', leg: null, name: restaurant.name, detail: restaurant.address, url: restaurant.kakaoUrl },
    cafe
      ? { icon: Coffee, label: '카페', leg: walk(legs[0], false), name: cafe.name, detail: cafe.category, url: cafe.kakaoUrl }
      : { icon: Coffee, label: '카페', leg: null, missing: '근처에 카페를 찾지 못했어요' },
    sight
      ? // the sight's leg is always the last one; without a cafe it starts at the restaurant
        { icon: Mountains, label: '볼거리', leg: walk(legs[legs.length - 1], !cafe), name: sight.name, detail: sight.category, url: sight.kakaoUrl }
      : { icon: Mountains, label: '볼거리', leg: null, missing: '근처에 볼거리를 찾지 못했어요' },
  ]

  return (
    <div>
      <p className="text-sm font-medium text-accent">데이트 코스</p>
      <h3 className="mt-2 text-2xl font-semibold tracking-tight">{restaurant.name}에서 시작해요</h3>
      {courses.length > 1 && (
        <div className="mt-6 flex flex-wrap gap-2">
          {courses.map((course, index) => (
            <button
              key={course.restaurant.id}
              type="button"
              onClick={() => setSelected(index)}
              className="relative max-w-full rounded-full px-4 py-2 text-sm font-medium transition active:scale-[0.98]"
            >
              {index === selected && (
                <motion.span layoutId="course-tab" className="absolute inset-0 rounded-full bg-zinc-900" transition={spring} />
              )}
              <span className={`relative block truncate ${index === selected ? 'text-white' : 'text-zinc-600'}`}>
                <span className="font-mono">{number(index)}</span> {course.restaurant.name}
              </span>
            </button>
          ))}
        </div>
      )}
      <AnimatePresence mode="wait">
        <motion.div
          key={selected}
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -8 }}
          transition={spring}
        >
          <ol className="mt-8 grid gap-8 md:grid-cols-3 md:gap-6">
            {steps.map((step, index) => (
              <motion.li
                key={step.label}
                initial={{ opacity: 0, y: 16 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ ...spring, delay: index * 0.15 }}
                className="border-l border-zinc-300 pl-6 md:border-l-0 md:border-t md:pl-0 md:pt-6"
              >
                {/* keeps the steps level on desktop even when a step has no leg */}
                <p className={`mb-3 flex h-4 items-center gap-1.5 font-mono text-xs text-zinc-500 ${step.leg ? '' : 'max-md:hidden'}`}>
                  {step.leg && (
                    <>
                      <PersonSimpleWalk size={14} /> {step.leg}
                    </>
                  )}
                </p>
                <p className="flex items-center gap-2 text-sm text-zinc-500">
                  <span className="font-mono text-accent">{number(index)}</span>
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
              <p className="text-sm text-zinc-500">직선거리 기준 예상 시간이에요</p>
            </div>
          )}
        </motion.div>
      </AnimatePresence>
    </div>
  )
}
```

- [ ] **Step 4: Wire it into `PlacesSection.tsx`**

Replace the import on line 7:

```tsx
import DateCourses from './DateCourses.tsx'
```

Replace the course block:

```tsx
            {state.data.dateCourses.length > 0 && (
              <div className="mt-16">
                <DateCourses courses={state.data.dateCourses} />
              </div>
            )}
```

(No reset code: every search sets `loading` first, which unmounts `DateCourses`, so a new result starts on course 1.)

- [ ] **Step 5: Run the type check and build**

Run: `cd frontend && npm run build 2>&1 | tail -3`
Expected: build succeeds (`✓ built in …`), no `error TS`.

- [ ] **Step 6: Write the browser check script**

`<workspace>/courses-check.mjs` (answers every `/api/*` call from fixtures, so no backend or keys are needed):

```js
// usage: node courses-check.mjs <courses|single|none|mobile>   (Vite dev server on :5173)
import { spawn } from 'node:child_process'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const scenario = process.argv[2] ?? 'courses'
const PORT = 9334
const chrome = spawn(
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${mkdtempSync(join(tmpdir(), 'courses-'))}`, '--window-size=1280,900', 'about:blank'],
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
const routeHref = () => js(`document.querySelector('a[href^="https://map.kakao.com/link/by/walk/"]')?.getAttribute('href') ?? null`)
const check = (label, ok) => {
  console.log(`${ok ? 'PASS' : 'FAIL'} ${label}`)
  if (!ok) process.exitCode = 1
}
const screenshot = async (name) =>
  writeFileSync(join(import.meta.dirname, `${name}.png`), Buffer.from((await send('Page.captureScreenshot', { captureBeyondViewport: true })).data, 'base64'))

const place = (id, name, meters, rating, reviewCount, priceLevel) => ({
  id, name, address: `서울 성동구 ${name}길 1`, distanceMeters: meters, lat: 37.54, lng: 127.05,
  kakaoUrl: `https://place.map.kakao.com/${id}`, rating, reviewCount, priceLevel, reviews: [],
  googleUrl: rating === null ? null : `https://maps.google.com/?cid=${id}`,
})
const spot = (id, name, category) => ({
  id, name, category, address: `서울 성동구 ${name}로 2`, lat: 37.545, lng: 127.045, kakaoUrl: `https://place.map.kakao.com/${id}`,
})
const ROUTE = 'https://map.kakao.com/link/by/walk/%EC%84%B1%EC%88%98%ED%83%84%ED%83%84,37.54,127.05/c1,37.543,127.05/s1,37.55,127.05'
const FAMOUS = [place('f1', '성수탄탄', 4200, 4.4, 1284, 2), place('f2', '담담', 8800, 4.7, 512, 3), place('f3', '옛날탄탄 성수역 본점 두번째 지점', 15300, null, null, null)]
const COURSES = [
  {
    restaurant: FAMOUS[0], cafe: spot('c1', '어니언 성수', '커피전문점'), sight: spot('s1', '서울숲', '공원'),
    legs: [{ from: '성수탄탄', to: '어니언 성수', meters: 320, walkMinutes: 5 }, { from: '어니언 성수', to: '서울숲', meters: 1240, walkMinutes: 19 }],
    routeUrl: ROUTE,
  },
  {
    restaurant: FAMOUS[1], cafe: null, sight: spot('s2', '뚝섬한강공원', '공원'),
    legs: [{ from: '담담', to: '뚝섬한강공원', meters: 780, walkMinutes: 12 }],
    routeUrl: 'https://map.kakao.com/link/by/walk/x,1,1/y,2,2',
  },
  { restaurant: FAMOUS[2], cafe: null, sight: null, legs: [], routeUrl: null },
]
const places = (dateCourses) => ({
  origin: { name: '현재 위치', lat: 37.54, lng: 127.05 },
  nearby: [place('n1', '탄탄공방', 180, 4.1, 212, 2)],
  famous: FAMOUS,
  dateCourses,
})
const FOOD = { id: 'tantanmen', name: '탄탄멘', description: '고소한 참깨 국물에 매콤한 고추기름.' }

function answer(url) {
  if (url.includes('/api/foods/random')) return [200, FOOD]
  const body = places(scenario === 'single' ? COURSES.slice(0, 1) : scenario === 'none' ? [] : COURSES)
  if (url.includes('near=')) return [200, { ...body, origin: { ...body.origin, name: '성수동' } }]
  return [200, body]
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

await send('Page.enable')
await send('Runtime.enable')
await send('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }] })
await send('Page.addScriptToEvaluateOnNewDocument', {
  source: `Object.defineProperty(navigator, 'geolocation', { value: { getCurrentPosition: (ok) => setTimeout(() => ok({ coords: { latitude: 37.54, longitude: 127.05, accuracy: 10 } }), 50) } })`,
})
if (scenario === 'mobile') await send('Emulation.setDeviceMetricsOverride', { width: 375, height: 812, deviceScaleFactor: 2, mobile: true })

try {
  await send('Page.navigate', { url: 'http://localhost:5173/' })
  await waitFor('오늘 뭐 먹지?')
  await click('랜덤으로 뽑기')
  await waitFor('이 메뉴, 어디서 먹지?')
  await click('현재 위치로')
  await waitFor('현재 위치 기준')

  if (scenario === 'courses') {
    await waitFor('성수탄탄에서 시작해요')
    let body = await text()
    check('three course tabs', body.includes('01 성수탄탄') && body.includes('02 담담') && body.includes('03 옛날탄탄'))
    check('legs above cafe and sight', body.includes('도보 5분 · 320m') && body.includes('도보 19분 · 1.2km'))
    check('route button with note', body.includes('전체 경로 보기') && body.includes('직선거리 기준 예상 시간이에요'))
    check('route link points at the course', (await routeHref()) === ROUTE)
    await screenshot('courses-desktop')
    await click('02 담담')
    await waitFor('담담에서 시작해요')
    await sleep(600)
    body = await text()
    check('cafe-less course walks from the restaurant', body.includes('식당에서 도보 12분 · 780m'))
    check('missing cafe marked', body.includes('근처에 카페를 찾지 못했어요'))
    await click('03 옛날탄탄')
    await waitFor('옛날탄탄 성수역 본점 두번째 지점에서 시작해요')
    await sleep(600)
    check('restaurant-only course hides the route', !(await text()).includes('전체 경로 보기') && (await routeHref()) === null)
    await type('성수동')
    await click('찾기')
    await waitFor('성수동 기준')
    await waitFor('성수탄탄에서 시작해요')
    check('new search starts on course 1', true)
  } else if (scenario === 'single') {
    await waitFor('성수탄탄에서 시작해요')
    check('one course hides the tabs', !(await text()).includes('01 성수탄탄'))
  } else if (scenario === 'none') {
    check('no course area without courses', !(await text()).includes('데이트 코스'))
  } else if (scenario === 'mobile') {
    await waitFor('성수탄탄에서 시작해요')
    await click('03 옛날탄탄')
    await waitFor('옛날탄탄 성수역 본점 두번째 지점에서 시작해요')
    await sleep(600)
    check('no horizontal scroll at 375px', (await js('document.documentElement.scrollWidth')) <= 375)
    await click('01 성수탄탄')
    await waitFor('성수탄탄에서 시작해요')
    await sleep(600)
    await screenshot('courses-mobile')
  }
} catch (error) {
  console.log(`FAIL ${error.message}`)
  process.exitCode = 1
} finally {
  ws.close()
  chrome.kill()
}
```

- [ ] **Step 7: Run every browser scenario**

Run (start Vite first, from the repo root; stop it afterwards):

```bash
(cd frontend && npm run dev -- --port 5173 --strictPort > /dev/null 2>&1 &) ; sleep 3
for s in courses single none mobile; do echo "== $s"; node <workspace>/courses-check.mjs $s; done
```

Expected: every line starts with `PASS` (no `FAIL`) across all four scenarios. Then open `courses-desktop.png` and `courses-mobile.png` from the workspace and look at them: tab pill on course 01, step numbers level on desktop, leg labels above steps 02/03, vertical timeline and wrapped tabs on mobile, no emoji.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/features/places
git commit -m "feat: show date course tabs with walking legs and route link

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
