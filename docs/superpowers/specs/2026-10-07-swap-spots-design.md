# Pick Your Food — H1. 코스의 카페·볼거리 바꾸기 설계

- 작성일: 2026-10-07
- 범위: 서브 프로젝트 H1 (검색 화면의 코스에서 카페·볼거리를 가까운 후보 중 하나로 바꾸기)
- 선행: C1(데이트 코스), C2b(저장·공유), G2(도보 경로와 코스 지도), G1(내 코스).
- 패키지 구조: `CLAUDE.md`의 package-by-feature 규칙. 백엔드는 `place`(후보, 구간 API)와 `account`(공개 경로 한 줄)가 바뀐다. 프론트엔드는 `features/places/`(요청과 상태), `features/saved/`(저장할 때 후보 빼기), `shared/places/`(코스 탭의 후보 목록)가 바뀐다.

## 1. 목표

지금 코스의 카페·볼거리는 서버가 고른 가장 가까운 곳으로 고정이다. 이번에는 검색 화면의 모든 코스(자동 코스와 내 코스)에서 카페·볼거리를 가까운 후보 최대 5곳 중 하나로 바꿀 수 있게 한다. 바꾸면 구간 시간·지도·전체 경로가 새 장소로 다시 계산되고, 바꾼 상태가 그대로 저장된다.

성공 기준:
- 카페·볼거리 칸의 `다른 곳`을 누르면 후보 목록(이름, 분류, 식당에서 거리, 지금 고른 곳 표시)이 펼쳐진다.
- 후보를 고르면 경로 계산이 끝나는 대로 그 칸과 구간·지도·`전체 경로 보기`가 함께 새 장소로 바뀐다.
- 바꾼 뒤 저장하면 저장 결과와 공유 링크에서도 바꾼 장소로 보인다.
- 저장 결과·공유 링크 화면에는 `다른 곳`이 없다.

## 2. 결정 사항

| 항목 | 결정 |
|------|------|
| 바꾸는 방식 | 후보 목록에서 고르기 |
| 바꿀 수 있는 코스 | 검색 화면의 모든 코스(자동 3 + 내 코스). 저장 결과·공유 링크는 보기만 |
| 후보 | 카카오 근처 검색 `size` 3 → 5. 코스 응답에 `cafes`·`sights`로 함께 보낸다(카카오 호출 수는 그대로) |
| 후보 중복 | 다른 코스가 쓰는 곳도 목록에 있다. 처음 고르기는 지금과 같다(자동 코스끼리 겹치지 않게, 내 코스는 가장 가까운 곳) |
| 구간 다시 계산 | 새 API `GET /api/places/legs`. 기존 TMAP 경로·직선 대체·캐시를 그대로 쓴다 |
| 캐시 안전 | 경로 캐시 키에 도착지 좌표를 더한다. 클라이언트가 보낸 좌표가 다른 코스의 캐시를 덮어쓰지 못한다 |
| 반영 시점 | 구간 응답이 오면 장소와 구간을 함께 바꾼다(미리 바꾸지 않는다). 버려진 요청이나 실패한 요청은 화면을 바꾸지 않는다 |
| 실패 | 구간 요청 실패 → 코스는 그대로, 알림. 직선 대체는 서버가 TMAP 실패 때만 한다 |
| 저장 | 후보 목록(`cafes`·`sights`)은 빼고 보낸다. 저장 형식은 그대로 |

## 3. 백엔드 (`place`)

### 후보

- `KakaoClient.nearby`의 `size`를 5로.
- `PlacesResponse.Spot`에 `Integer distanceMeters`(카카오가 준 검색 중심, 즉 식당에서의 거리). 응답의 모든 `Spot`에 채운다.
- `PlacesResponse.DateCourse`와 `Course`에 `List<Spot> cafes`, `List<Spot> sights`(가까운 순, 최대 5, 검색 실패 시 `[]`). `cafe`·`sight`는 지금처럼 이 중 고른 하나(또는 `null`).
- 저장 쪽 `SavedResult.Spot`은 바꾸지 않는다(`distanceMeters`는 저장되지 않는다. 저장 결과 화면은 거리를 쓰지 않는다).

### API: `GET /api/places/legs?id=…&name=…&lat=…&lng=…&id=……`

- 들르는 곳을 순서대로(식당, 카페, 볼거리 중 있는 것) 같은 이름 파라미터를 반복해 보낸다. `id`·`name`·`lat`·`lng` 네 목록의 길이가 같고 2~3개.
- 로그인 불필요. `SecurityConfig`의 `permitAll`에 `/api/places/legs`(`/api/places/course`와 같은 이유).
- 검사(위반 시 400): 길이 조건, 각 `id`·`name`이 비어 있지 않고(공백 제거 후) 100자 이하이며 제어 문자 없음, `lat` -90~90, `lng` -180~180(NaN 거부). 누락·숫자 아님은 Spring 기본 400. `PlaceController`의 기존 `fits`를 쓴다.
- 응답 `PlacesResponse.Walk(List<Leg> legs, String routeUrl)`. JSON은 코스 응답의 같은 필드와 똑같은 모양.
- `PlaceService.walk(List<PlaceService.Stop> stops)`: 기존 `courseAsync`에서 구간·`routeUrl` 만드는 부분을 떼어 두 경로가 함께 쓴다(코스는 그 결과에 카페·볼거리를 붙인다).

### 경로 캐시 키

- `from.id@from.lat,from.lng>to.id@to.lat,to.lng`. 도착지도 좌표로 묶어, 진짜 카페 id에 가짜 좌표를 붙인 요청이 그 카페로 가는 경로 캐시를 오염시키지 못한다.

## 4. 화면

### 타입 (`shared/places/types.ts`)

- `Spot.distanceMeters?: number`(저장 결과에는 없다).
- `DateCourse.cafes?: Spot[]`, `sights?: Spot[]`(저장 결과에는 없다).

### 코스 탭 (`shared/places/DateCourses.tsx`)

- 선택 prop `swap?: { change: (course: number, kind: 'cafe' | 'sight', spot: Spot) => void; pending: number | null; failed: number | null }`(값은 코스 번호). 없으면(저장 결과 화면) 버튼을 그리지 않는다.
- 카페·볼거리 칸: 후보가 2곳 이상이면 `카카오맵` 링크 옆에 `다른 곳` 버튼(Phosphor `ArrowsClockwise`). 누르면 칸 아래에 후보 목록이 펼쳐지고 다시 누르면 접힌다. 한 번에 한 칸만 펼친다.
  - 각 줄: 이름, 분류, `distance(distanceMeters)`. 지금 고른 곳은 `지금` 표시와 함께 누를 수 없다.
  - 고르면 목록이 접히고 `change`를 부른다.
- 카페·볼거리를 못 찾은 칸(`missing`)은 지금처럼 문구만(후보가 없으므로 버튼 없음).
- 진행 중(`pending === selected`): 그 코스의 구간 줄에 `경로 다시 계산 중…`, `다른 곳` 버튼 비활성.
- 실패(`failed === selected`): 코스 아래 `바꾸지 못했어요. 다시 시도해 주세요.`(`role="alert"`).

### 상태와 요청 (`features/places/`)

- `api.ts`: `fetchLegs(stops: { id: string; name: string; lat: number; lng: number }[]): Promise<{ legs: Leg[]; routeUrl: string | null }>`.
- `PlacesSection.tsx`의 `change(course, kind, spot)`:
  - `pending = course`로 두고, 그 코스의 `cafe` 또는 `sight`를 `spot`으로 바꾼 들르는 곳(식당·카페·볼거리 중 있는 것)으로 `fetchLegs`. 화면의 코스는 아직 바꾸지 않는다.
  - 성공: 바꾼 장소와 `legs`·`routeUrl`을 함께 넣은 결과로 상태를 바꾸고 `onLoaded`로 올린다(저장 대상 갱신). `pending = null`.
  - 실패: 코스는 그대로, `failed = course`.
  - 더 늦게 누른 것만 반영한다. 내 코스 만들기와 같은 카운터(`latestCourse`) 하나를 쓴다: 바꾸기·내 코스 만들기 중 나중에 누른 것이 이기고, 먼저 누른 것의 응답은 버린다. 새 검색도 진행 중인 결과를 버린다. 시작할 때 다른 쪽의 진행·실패 표시는 지운다.
  - 코스 탭은 바꿀 때 다시 그리지 않는다(지금 선택한 탭 유지). `key`는 그대로 두고 `courses` prop만 바뀐다.

### 저장 (`features/saved/api.ts`)

- 저장 요청에서 각 코스의 `cafes`·`sights`를 빼고, `cafe`·`sight`의 `distanceMeters`도 뺀다(서버 저장 형식과 맞춘다).

## 5. 테스트

백엔드(`./gradlew test`):
- `PlaceServiceTest`:
  - 자동 코스·내 코스 응답에 후보 `cafes`·`sights`가 가까운 순으로 담기고, 고른 `cafe`가 그중 하나다. 다른 코스가 쓰는 곳도 목록에 있다.
  - 카페 검색 실패 → `cafes = []`, `cafe = null`.
  - `legs`: 3곳 → 구간 2개, `routeUrl`이 세 곳을 잇는다. 2곳 → 구간 1개.
  - 캐시: 같은 도착지 id에 다른 좌표로 계산한 경로가 진짜 좌표의 경로를 덮어쓰지 않는다.
- `PlaceControllerTest`:
  - 로그인 없이 `GET /api/places/legs` 3곳 200, JSON에 `legs`·`routeUrl`.
  - 1곳, 4곳, 목록 길이 다름, 빈 `id`, 101자 `name`, 제어 문자 `name`, `lat=91`, `lng=-181` → 400.

화면: `npm run build && npm run lint` + 헤드리스 Chrome(가로챈 `/api/*`). 확인 항목:
1. `다른 곳` → 후보 목록(지금 표시) → 다른 카페 선택 → `경로 다시 계산 중…` → 칸·구간·`전체 경로 보기`가 새 장소로.
2. `/api/places/legs` 500 → 카페는 그대로, 알림.
3. 빠르게 두 번 바꾸면 마지막 것만 남는다(첫 응답을 늦게 돌려줘도).
4. 바꾼 뒤 저장 → 요청 본문에 `cafes`·`sights`·`distanceMeters`가 없고 바꾼 카페가 있다.
5. 다른 코스 탭으로 옮겨도 바꾼 상태가 남는다.
6. 저장 결과 화면에는 `다른 곳`이 없다.
7. 375px에서 후보 목록이 가로로 넘치지 않는다.
8. 바꾸기 요청 중 내 코스를 만들면(또는 그 반대) 나중 것만 반영된다.

## 6. 범위 밖

- 저장 결과·공유 링크 화면에서 바꾸기.
- 후보 5곳보다 더 보기, 카페·볼거리 검색 반경 바꾸기.
- 식당 바꾸기(그건 G1의 `이 식당으로 코스 만들기`).
- 들르는 곳 순서 바꾸기, 장소 더하기.
