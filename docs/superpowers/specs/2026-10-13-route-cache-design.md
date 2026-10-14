# Pick Your Food — H5. 도보 경로를 DB에 캐시 설계

- 작성일: 2026-10-13
- 범위: 서브 프로젝트 H5 (TMAP 도보 경로 결과를 DB에 두어 Render 재시작 뒤에도 다시 쓴다)
- 선행: 도보 경로(TMAP), H1(코스 바꾸기).
- 패키지 구조: `CLAUDE.md`의 package-by-feature 규칙. 백엔드 `place`(`PlaceService`, 새 `RouteRepository`)와 마이그레이션 V5만 바뀐다. 화면·API 응답·저장 형식은 바뀌지 않는다.

## 1. 목표

지금 도보 경로는 메모리(`ConcurrentHashMap`, 24시간)에만 있어서 Render가 재시작할 때마다 비고, 같은 코스에도 TMAP을 다시 부른다. 경로를 DB에 두어 재시작 뒤에도 다시 쓰고, TMAP 호출을 줄인다.

성공 기준:
- 한 번 받은 경로는 30일 동안 앱이 재시작해도 TMAP을 부르지 않고 DB에서 나온다.
- 실패한 경로는 저장하지 않는다(TMAP 장애 뒤 다음 검색에서 다시 부른다).
- DB를 읽거나 쓰다 실패해도 검색 결과는 지금처럼 나온다(TMAP 결과나 직선).
- 30일 지난 행은 지워져 테이블이 끝없이 커지지 않는다.

## 2. 결정 사항

| 항목 | 결정 |
|------|------|
| 다시 쓰는 기간 | 30일 (`ROUTE_TTL = Duration.ofDays(30)`) |
| 키 | 지금 메모리 캐시와 같은 문자열 `출발id@위도,경도>도착id@위도,경도` |
| 경로 좌표 | 줄인 좌표(최대 200점, 소수 5자리)를 JSON 문자열로 |
| 오래된 행 정리 | 새 경로를 저장할 때 30일 지난 행을 지운다 |
| 같은 키 동시 저장 | `DuplicateKeyException`은 무시(먼저 들어간 행을 쓴다) |
| DB 오류 | 경고 로그만 남기고 캐시가 없는 것처럼 계속 |
| Google 정보 캐시 | 그대로 메모리, 24시간 |

## 3. 백엔드 (`place`)

### 마이그레이션 `db/migration/V5__walking_route.sql`

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

- id는 `PlaceController`에서 100자로 제한되므로 키는 300자 안쪽이다.
- 200점 경로는 JSON으로 약 4.4KB다.
- H2와 PostgreSQL에서 문법이 같아 `db/migration/`에 둔다.

### `RouteRepository` (새 파일, `@Repository`, `JdbcClient` + `JsonMapper`)

- `Optional<Route> find(String key, Instant since)`: `created_at >= since`인 행만 읽는다.
- `void save(String key, Route route)`:
  - 먼저 `created_at < now - ROUTE_TTL`인 행을 지운다.
  - 그다음 넣는다.
  - `DuplicateKeyException`은 무시한다.

### `PlaceService`

- 생성자에 `RouteRepository`를 더한다.
- `route(from, to)`:
  1. `routeCache.find(key, now - ROUTE_TTL)`. 있으면 그대로 돌려준다.
  2. 없으면 TMAP을 부르고 `thin`한다.
  3. 성공하면 `routeCache.save`로 저장한다.
  4. 읽기와 쓰기는 각각 `try`로 감싼다. 실패하면 `log.warn`을 남기고 캐시가 없는 것처럼 계속한다.
  5. TMAP 실패는 지금처럼 빈 값을 돌려준다(직선).
- `routes` 맵과 `CachedRoute`, 그 주석을 지운다.
- `CACHE_TTL`(24시간)은 Google 정보 캐시에만 남는다.

## 4. 테스트

백엔드(`./gradlew test`):
- `RouteRepositoryTest`(H2, `@SpringBootTest` 또는 `@JdbcTest`):
  - 저장한 뒤 읽으면 meters, seconds, path가 같다.
  - `since`보다 오래된 행은 읽히지 않는다.
  - 저장할 때 30일 지난 행이 지워진다.
  - 같은 키를 두 번 저장해도 예외가 없다.
- `PlaceServiceTest`(`RouteRepository`는 mock):
  - 저장된 경로가 있으면 TMAP을 부르지 않는다.
  - TMAP 성공은 저장하고, 실패나 키 없음은 저장하지 않는다.
  - `find`나 `save`가 예외를 던져도 경로(또는 직선)가 나온다.
- `PostgresMigrationTest`(Docker가 있을 때): V5까지 PostgreSQL에서 돈다.

## 5. 범위 밖

- Google 정보 캐시를 DB로 옮기기.
- 캐시 통계·관리 화면.
- 주기적인 정리 작업(저장할 때 지우는 것으로 충분하다).
