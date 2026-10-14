# H5 도보 경로 DB 캐시 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** TMAP 도보 경로를 `walking_route` 테이블에 30일 동안 두어, Render가 재시작해도 같은 경로에 TMAP을 다시 부르지 않게 한다.

**Architecture:**
- 마이그레이션 V5가 테이블을 만든다.
- `place` 패키지의 새 `RouteRepository`(JdbcClient + JsonMapper)가 경로를 읽고 쓴다.
- `PlaceService.route()`는 메모리 맵 대신 이 저장소를 쓴다. DB 오류는 경고만 남기고 넘어간다.

**Tech Stack:** Spring Boot 4.1.1, Java 17, JdbcClient, Flyway, Jackson 3(`tools.jackson`), JUnit 5 + Mockito + AssertJ, 테스트 DB H2.

**Spec:** `docs/superpowers/specs/2026-10-13-route-cache-design.md`

## Global Constraints

- Gradle 전에 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- 재사용 기간: `static final Duration ROUTE_TTL = Duration.ofDays(30);` (`PlaceService`).
- 키: 지금 그대로 `from.id() + "@" + from.lat() + "," + from.lng() + ">" + to.id() + "@" + to.lat() + "," + to.lng()`.
- 성공한 경로만 저장한다. TMAP 실패, 키 없음은 저장하지 않는다.
- DB를 읽거나 쓰다 실패하면 `log.warn`을 남기고 캐시가 없는 것처럼 계속한다.
- Google 정보 캐시(`cache`, `CACHE_TTL` 24시간)는 바꾸지 않는다.
- 화면, API 응답, 저장 형식은 바꾸지 않는다.
- package-by-feature: 새 코드는 모두 `com.example.pickyourfood.place`.
- `.superpowers/`, `.claude/`, `.serena/`, `graphify-out/`, `data/`는 커밋하지 않는다.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. 가장 긴 키와 경로. id 100자(한글) 두 개와 긴 좌표로 된 키, 200점 경로가 컬럼에 들어가야 한다. → Task 1 `longestKeyAndPathFit`.
2. 같은 코스를 동시에 검색. 같은 키를 두 번 저장해도 예외 없이 먼저 저장된 경로가 남아야 한다. → Task 1 `savingTheSameKeyTwiceKeepsTheFirst`.
3. DB가 죽었거나 테이블이 없을 때. 검색은 TMAP 경로로 그대로 나와야 한다. → Task 2 `databaseFailureStillReturnsTheTmapRoute`.
4. 30일이 지난 경로. 읽히지 않고, 다음 저장 때 지워져야 한다. → Task 1 `routeOlderThanSinceIsNotRead`, `savingDeletesRoutesOlderThanTheTtl`.
5. 가짜 좌표로 남이 넣은 경로. 진짜 식당의 경로를 덮으면 안 된다(키에 좌표가 든다). → Task 2에서 기존 `...DoesNotPoisonTheRouteCache...` 테스트가 DB 대역으로 계속 통과.

---

### Task 1: `walking_route` 테이블과 `RouteRepository`

**Files:**
- Create: `src/main/resources/db/migration/V5__walking_route.sql`
- Create: `src/main/java/com/example/pickyourfood/place/RouteRepository.java`
- Create: `src/test/java/com/example/pickyourfood/place/RouteRepositoryTest.java`
- Modify: `src/test/java/com/example/pickyourfood/account/PostgresMigrationTest.java`. `everyMigrationRunsOnPostgres`의 `containsExactly("1", "2", "3", "4")`를 `containsExactly("1", "2", "3", "4", "5")`로 바꾼다.
- Modify: `src/main/java/com/example/pickyourfood/place/PlaceService.java`. `ROUTE_TTL` 상수 한 줄만 더한다.

**Interfaces:**
- Consumes: `TmapClient.Route(int meters, int seconds, List<double[]> path)`
- Produces:
  - `RouteRepository(JdbcClient jdbc, JsonMapper json)`: 패키지 전용 생성자, `@Repository`.
  - `Optional<Route> find(String key, Instant since)`: 패키지 전용, final이 아니다(Task 2 테스트가 상속한다).
  - `void save(String key, Route route)`: 패키지 전용, final이 아니다.
  - `PlaceService.ROUTE_TTL`: `static final Duration`, 패키지 전용.

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`RouteRepositoryTest.java`:

```java
package com.example.pickyourfood.place;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pickyourfood.place.TmapClient.Route;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class RouteRepositoryTest {

	private static final List<double[]> PATH = List.of(new double[] { 37.56, 127.05 }, new double[] { 37.564, 127.05 });

	@Autowired
	RouteRepository routes;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void empty() {
		jdbc.sql("delete from walking_route").update();
	}

	private static Instant since() {
		return Instant.now().minus(PlaceService.ROUTE_TTL);
	}

	private void storedAt(String key, Instant at) {
		jdbc.sql("insert into walking_route (route_key, meters, seconds, path, created_at) values (?, ?, ?, ?, ?)")
				.params(key, 1, 60, "[[37.0,127.0]]", Timestamp.from(at))
				.update();
	}

	@Test
	void savedRouteIsReadBack() {
		routes.save("a>b", new Route(512, 421, PATH));

		Route route = routes.find("a>b", since()).orElseThrow();

		assertThat(route.meters()).isEqualTo(512);
		assertThat(route.seconds()).isEqualTo(421);
		assertThat(route.path()).containsExactlyElementsOf(PATH);
	}

	@Test
	void unknownKeyIsEmpty() {
		assertThat(routes.find("nowhere", since())).isEmpty();
	}

	@Test
	void routeOlderThanSinceIsNotRead() {
		storedAt("old", Instant.now().minus(Duration.ofDays(31)));

		assertThat(routes.find("old", since())).isEmpty();
	}

	@Test
	void savingDeletesRoutesOlderThanTheTtl() {
		storedAt("old", Instant.now().minus(Duration.ofDays(31)));
		storedAt("recent", Instant.now().minus(Duration.ofDays(29)));

		routes.save("new", new Route(512, 421, PATH));

		assertThat(jdbc.sql("select route_key from walking_route").query(String.class).list())
				.containsExactlyInAnyOrder("new", "recent");
	}

	@Test
	void savingTheSameKeyTwiceKeepsTheFirst() {
		routes.save("a>b", new Route(512, 421, PATH));
		routes.save("a>b", new Route(1, 60, PATH));

		assertThat(routes.find("a>b", since()).orElseThrow().meters()).isEqualTo(512);
	}

	@Test
	void longestKeyAndPathFit() {
		// ids are capped at 100 characters by PlaceController; coordinates print up to 17 digits
		String id = "가".repeat(100);
		String key = id + "@37.123456789012345,127.12345678901234>" + id + "@37.123456789012345,127.12345678901234";
		List<double[]> path = Collections.nCopies(PlaceService.MAX_PATH_POINTS, new double[] { 37.12345, 127.12345 });

		routes.save(key, new Route(9_999, 9_999, path));

		assertThat(routes.find(key, since()).orElseThrow().path()).hasSize(PlaceService.MAX_PATH_POINTS);
	}
}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew test --tests 'com.example.pickyourfood.place.RouteRepositoryTest'`
Expected: 컴파일 실패(`RouteRepository`, `ROUTE_TTL`이 없다).

- [ ] **Step 3: 마이그레이션, 상수, 저장소를 쓴다**

`V5__walking_route.sql`:

```sql
create table walking_route (
	route_key varchar(400) primary key,
	meters integer not null,
	seconds integer not null,
	path varchar(16000) not null,
	created_at timestamp not null
);

create index walking_route_created on walking_route (created_at);
```

`PlaceService.java`: `CACHE_TTL` 줄 바로 아래에 더한다.

```java
	// walking routes rarely change, and the database keeps them across restarts
	static final Duration ROUTE_TTL = Duration.ofDays(30);
```

`RouteRepository.java`:

```java
package com.example.pickyourfood.place;

import com.example.pickyourfood.place.TmapClient.Route;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

// TMAP walking routes, kept across restarts so a search doesn't spend the daily quota again
@Repository
class RouteRepository {

	private final JdbcClient jdbc;
	private final JsonMapper json;

	RouteRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	Optional<Route> find(String key, Instant since) {
		return jdbc.sql("select meters, seconds, path from walking_route where route_key = ? and created_at >= ?")
				.params(key, Timestamp.from(since))
				.query((rs, row) -> new Route(rs.getInt("meters"), rs.getInt("seconds"),
						List.of(json.readValue(rs.getString("path"), double[][].class))))
				.optional();
	}

	// stale rows go on every save, so the table stays at about a month of routes
	void save(String key, Route route) {
		Instant now = Instant.now();
		jdbc.sql("delete from walking_route where created_at < ?").param(Timestamp.from(now.minus(PlaceService.ROUTE_TTL))).update();
		try {
			jdbc.sql("insert into walking_route (route_key, meters, seconds, path, created_at) values (?, ?, ?, ?, ?)")
					.params(key, route.meters(), route.seconds(), json.writeValueAsString(route.path()), Timestamp.from(now))
					.update();
		} catch (DuplicateKeyException e) {
			// another search saved the same leg first; its route is just as good
		}
	}
}
```

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew test --tests 'com.example.pickyourfood.place.RouteRepositoryTest'`
Expected: 6개 모두 PASS.

`PostgresMigrationTest`의 기대 버전을 `"1", "2", "3", "4", "5"`로 바꾼다.

Run: `./gradlew test --tests 'com.example.pickyourfood.account.PostgresMigrationTest'`
Expected:
- Docker가 돌고 있으면 PASS.
- Docker가 없으면 skipped. 보고서에 어느 쪽인지 적는다.

- [ ] **Step 5: 커밋한다**

```bash
git add src/main/resources/db/migration/V5__walking_route.sql \
  src/main/java/com/example/pickyourfood/place/RouteRepository.java \
  src/main/java/com/example/pickyourfood/place/PlaceService.java \
  src/test/java/com/example/pickyourfood/place/RouteRepositoryTest.java \
  src/test/java/com/example/pickyourfood/account/PostgresMigrationTest.java
git commit -m "feat: store walking routes in the database

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `PlaceService`가 DB의 경로를 쓴다

**Files:**
- Modify: `src/main/java/com/example/pickyourfood/place/PlaceService.java`. 바꿀 곳은 다음과 같다.
  - 필드 `routes`
  - 생성자
  - `route(Stop from, Stop to)`
  - `record CachedRoute`
- Modify: `src/test/java/com/example/pickyourfood/place/PlaceServiceTest.java`

**Interfaces:**
- Consumes (Task 1):
  - `RouteRepository.find(String key, Instant since)`
  - `RouteRepository.save(String key, Route route)`
  - `RouteRepository(JdbcClient, JsonMapper)`
  - `PlaceService.ROUTE_TTL`
- Produces: `PlaceService(KakaoClient kakao, GooglePlacesClient google, TmapClient tmap, RouteRepository routes)`.

- [ ] **Step 1: 테스트를 DB 대역으로 바꾸고 새 테스트를 쓴다**

`PlaceServiceTest.java`에서 할 일:
- import를 더한다: `java.time.Instant`, `java.util.Map`, `java.util.concurrent.ConcurrentHashMap`, `static org.mockito.ArgumentMatchers.any`, `static org.mockito.Mockito.doThrow`, `org.springframework.dao.DataAccessResourceFailureException`.
- `service` 필드를 다음으로 바꾼다.

```java
	// a mock answers Optional.empty(), so tests that don't stub it get straight-line legs
	private final TmapClient tmap = mock(TmapClient.class);
	private final MemoryRoutes routes = new MemoryRoutes();
	private final PlaceService service = new PlaceService(kakao, google, tmap, routes);

	// stands in for the walking_route table; searches look up legs on several threads
	static class MemoryRoutes extends RouteRepository {
		final Map<String, Route> saved = new ConcurrentHashMap<>();
		volatile Instant since;

		MemoryRoutes() {
			super(null, null);
		}

		@Override
		Optional<Route> find(String key, Instant since) {
			this.since = since;
			return Optional.ofNullable(saved.get(key));
		}

		@Override
		void save(String key, Route route) {
			saved.put(key, route);
		}
	}
```

`failedRouteFallsBackToTheStraightLineAndIsNotCached`의 마지막 `verify` 다음 줄에 더한다.

```java
		assertThat(routes.saved).isEmpty();
```

`routesAreCachedPerPairOfStops` 다음에 테스트를 더한다.

```java
	@Test
	void savedRouteIsUsedWithoutCallingTmap() {
		famousRanked("r1");
		cafesNear(37.56, spot("cafe", 37.564));
		routes.saved.put("r1@37.56,127.05>cafe@37.564,127.05", new Route(512, 421, WALK));

		Leg leg = service.search("ramen", ORIGIN).dateCourses().get(0).legs().get(0);

		assertThat(leg.meters()).isEqualTo(512);
		assertThat(leg.path()).containsExactlyElementsOf(WALK);
		verify(tmap, never()).route(anyDouble(), anyDouble(), anyDouble(), anyDouble());
	}

	@Test
	void routesAreReadBackForThirtyDays() {
		famousRanked("r1");
		cafesNear(37.56, spot("cafe", 37.564));

		Instant before = Instant.now();
		service.search("ramen", ORIGIN);

		assertThat(routes.since).isBetween(before.minus(PlaceService.ROUTE_TTL), Instant.now().minus(PlaceService.ROUTE_TTL));
	}

	@Test
	void tmapRouteIsSavedUnderBothEndsAndTheirCoordinates() {
		famousRanked("r1");
		cafesNear(37.56, spot("cafe", 37.564));
		when(tmap.route(37.56, 127.05, 37.564, 127.05)).thenReturn(Optional.of(new Route(512, 421, WALK)));

		service.search("ramen", ORIGIN);

		assertThat(routes.saved).containsOnlyKeys("r1@37.56,127.05>cafe@37.564,127.05");
	}

	@Test
	void noRouteIsSavedWithoutATmapKey() {
		famousRanked("r1");
		cafesNear(37.56, spot("cafe", 37.564));

		service.search("ramen", ORIGIN);

		assertThat(routes.saved).isEmpty();
	}

	@Test
	void databaseFailureStillReturnsTheTmapRoute() {
		RouteRepository broken = mock(RouteRepository.class);
		when(broken.find(anyString(), any())).thenThrow(new DataAccessResourceFailureException("database down"));
		doThrow(new DataAccessResourceFailureException("database down")).when(broken).save(anyString(), any());
		PlaceService withBrokenDatabase = new PlaceService(kakao, google, tmap, broken);
		famousRanked("r1");
		cafesNear(37.56, spot("cafe", 37.564));
		when(tmap.route(37.56, 127.05, 37.564, 127.05)).thenReturn(Optional.of(new Route(512, 421, WALK)));

		Leg leg = withBrokenDatabase.search("ramen", ORIGIN).dateCourses().get(0).legs().get(0);

		assertThat(leg.meters()).isEqualTo(512);
		verify(broken).save(eq("r1@37.56,127.05>cafe@37.564,127.05"), any());
	}
```

- [ ] **Step 2: 실패를 본다**

Run: `./gradlew test --tests 'com.example.pickyourfood.place.PlaceServiceTest'`
Expected: 컴파일 실패(`PlaceService`에 4인자 생성자가 없다).

- [ ] **Step 3: `PlaceService`를 고친다**

`routes` 필드와 그 위 주석 두 줄을 지우고, `tmap` 필드 아래에 더한다.

```java
	// successful routes only, so a TMAP outage is retried on the next search
	private final RouteRepository routes;
```

생성자:

```java
	PlaceService(KakaoClient kakao, GooglePlacesClient google, TmapClient tmap, RouteRepository routes) {
		this.kakao = kakao;
		this.google = google;
		this.tmap = tmap;
		this.routes = routes;
	}
```

`route()`와 그 바로 아래 두 helper:

```java
	// TMAP's walking route, thinned; empty without a key or when TMAP fails, and the leg falls back to the straight line
	private Optional<Route> route(Stop from, Stop to) {
		// both ends with their coordinates, so client-chosen stops can't poison the entry a real course reads
		String key = from.id() + "@" + from.lat() + "," + from.lng() + ">" + to.id() + "@" + to.lat() + "," + to.lng();
		Optional<Route> saved = savedRoute(key);
		if (saved.isPresent()) return saved;
		try {
			Optional<Route> route = tmap.route(from.lat(), from.lng(), to.lat(), to.lng())
					.map(found -> new Route(found.meters(), found.seconds(), thin(found.path())));
			route.ifPresent(found -> saveRoute(key, found));
			return route;
		} catch (RuntimeException e) {
			log.warn("TMAP route failed from {} to {}: {}", from.name(), to.name(), e.getMessage());
			return Optional.empty();
		}
	}

	// a database failure only costs a TMAP call, never the search
	private Optional<Route> savedRoute(String key) {
		try {
			return routes.find(key, Instant.now().minus(ROUTE_TTL));
		} catch (RuntimeException e) {
			log.warn("Route cache read failed: {}", e.getMessage());
			return Optional.empty();
		}
	}

	private void saveRoute(String key, Route route) {
		try {
			routes.save(key, route);
		} catch (RuntimeException e) {
			log.warn("Route cache write failed: {}", e.getMessage());
		}
	}
```

`private record CachedRoute(Route route, Instant at) { }`를 지운다. 쓰지 않게 된 import가 생기면 지운다(`Map`과 `ConcurrentHashMap`은 `cache`가 계속 쓴다).

- [ ] **Step 4: 통과를 본다**

Run: `./gradlew test --tests 'com.example.pickyourfood.place.PlaceServiceTest'`
Expected: 모두 PASS. 기존 테스트도 함께 통과해야 한다.
- `routesAreCachedPerPairOfStops`
- `pickedCourseWithAFakeLocationDoesNotPoisonTheRouteCacheForTheRealRestaurant`
- `walkWithAFakeCafeLocationDoesNotPoisonTheRouteCacheForTheRealCafe`

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. 전체가 통과해야 한다. Spring 컨텍스트가 `RouteRepository`를 주입한다.

- [ ] **Step 5: 커밋한다**

```bash
git add src/main/java/com/example/pickyourfood/place/PlaceService.java \
  src/test/java/com/example/pickyourfood/place/PlaceServiceTest.java
git commit -m "feat: reuse walking routes from the database for 30 days

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
