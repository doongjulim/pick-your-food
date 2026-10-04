# Pick Your Food — G1. 내가 고른 식당으로 데이트 코스 만들기 설계

- 작성일: 2026-10-04
- 범위: 서브 프로젝트 G1 (맛집 표에서 고른 식당에서 시작하는 데이트 코스 하나)
- 선행: C1(데이트 코스), C2b(저장·공유), G2(도보 경로와 코스 지도).
- 패키지 구조: `CLAUDE.md`의 package-by-feature 규칙. 백엔드는 `place`(코스 만들기)와 `saved`(저장 형식), `account`(공개 경로 한 줄)가 바뀐다. 프론트엔드는 `features/places/`(요청과 상태)와 `shared/places/`(표의 버튼, 코스 탭)가 바뀐다.

## 1. 목표

지금 코스는 근교 맛집 상위 3곳에서만 자동으로 만들어진다. 이번에는 사용자가 맛집 표(주위 1km·근교 탭 모두)에서 식당 하나를 골라, 그 식당에서 시작하는 식사 → 카페 → 볼거리 코스를 만들 수 있게 한다. 카페·볼거리 고르기, 도보 경로, 지도는 자동 코스와 같다.

성공 기준:
- 표의 행을 펼치면 "이 식당으로 코스 만들기"가 있고, 누르면 그 식당의 코스가 코스 탭 맨 앞에 `내 코스` 표시와 함께 선택된 채 나온다.
- 이미 코스가 있는 식당을 고르면 요청 없이 그 탭으로 간다.
- 내 코스는 한 번에 하나다. 다른 식당을 고르면 바뀐다.
- 저장하면 내 코스도 저장되고, 저장 결과와 공유 링크에서도 `내 코스`로 보인다.
- 자동 코스가 하나도 없던 검색에서도 내 코스를 만들 수 있다.

## 2. 결정 사항

| 항목 | 결정 |
|------|------|
| 고르는 범위 | 식당만. 카페·볼거리는 지금처럼 가장 가까운 곳 |
| 식당 정보 전달 | 화면이 가진 `id`·`name`·`lat`·`lng`를 쿼리로 보낸다. 카카오에는 id로 장소를 찾는 API가 없다 |
| 응답 | 식당을 뺀 코스 부분 `{cafe, sight, legs, routeUrl}`. 식당은 화면이 이미 가진 `Place`를 쓴다 |
| 중복 제외 | 하지 않는다. 자동 코스와 카페·볼거리가 겹쳐도 가장 가까운 곳 |
| 내 코스 수 | 하나. 새로 고르면 바뀐다 |
| 표시 | `DateCourse.picked: true`. 응답·저장 모두 필드가 없으면 자동 코스 |
| 저장 상한 | 코스 3개 → 4개(내 코스 1 + 자동 3), `picked` 코스는 최대 1개 |

## 3. 백엔드

### API: `GET /api/places/course?id=…&name=…&lat=…&lng=…` (`place`)

- 로그인 불필요. `SecurityConfig`의 `permitAll` 목록에 `/api/places/course`를 더한다(`/api/places`와 같은 이유: 비로그인 사용자도 검색한다).
- 검사(위반 시 400): `id`·`name`이 비어 있지 않고(공백 제거 후) 100자 이하, `lat`은 -90~90, `lng`는 -180~180. 누락·숫자 아님은 Spring 기본 400.
- 응답 `PlacesResponse.Course(Spot cafe, Spot sight, List<Leg> legs, String routeUrl)`. JSON은 자동 코스의 같은 필드와 똑같은 모양.
- `PlaceService.course(String id, String name, double lat, double lng)`:
  - 카페(`CE7`, 500m)·볼거리(`AT4`, 1,000m) 후보를 병렬로 찾고(기존 `candidatesAsync`), 각각 가장 가까운 곳(`pick`에 빈 `used`).
  - 구간·경로·`routeUrl`은 기존 코스와 같은 코드(`legAsync`, TMAP 경로, 실패 시 직선, 성공한 경로 캐시).
  - 카페·볼거리 검색 실패는 그 단계만 `null`(자동 코스와 같다). 둘 다 없으면 `legs = []`, `routeUrl = null`.
  - 기존 `courseAsync`는 식당 대신 출발 `Stop`을 받도록 바꿔 두 경로가 함께 쓴다.

### 저장 형식 (`saved`)

- `SavedResult.DateCourse`에 `Boolean picked`(없으면 `null` = 자동 코스).
- `SavedController.valid`: `fits(p.dateCourses(), 4)`, `picked`가 `true`인 코스 최대 1개.
- 크기: 코스 하나는 경로 최대 2구간 × 200점(약 8KB). 4개여도 `MAX_PAYLOAD`(64KB) 안이다.

## 4. 화면

### 맛집 표 (`shared/places/PlaceTable.tsx`)

- 선택 prop `course?: { pick: (place: Place) => void; pending: string | null; failed: string | null }`(값은 식당 id). 없으면(저장 결과 화면) 버튼을 그리지 않는다.
- 펼친 행의 링크 묶음(`카카오맵`, `Google 지도`) 아래에 `이 식당으로 코스 만들기` 버튼(Phosphor `Path`, 강조색 테두리).
  - 진행 중(`pending === place.id`): 비활성, `코스 만드는 중…`.
  - 실패(`failed === place.id`): 버튼 아래 `코스를 만들지 못했어요. 다시 시도해 주세요.`(`role="alert"`), 버튼은 다시 누를 수 있다.

### 코스 탭 (`shared/places/DateCourses.tsx`)

- `picked` 코스의 탭은 번호 뒤에 `내 코스`(작은 강조색 글자), 제목 위 라벨은 `데이트 코스` 대신 `내 데이트 코스`.
- 선택 prop `initial?: number`(처음 선택할 코스, 기본 0). 부모가 `key`를 바꿔 다시 그리게 한다.

### 상태와 요청 (`features/places/`)

- `api.ts`: `fetchCourse(place: Place): Promise<DateCourse>` — `/api/places/course` 응답에 `restaurant: place`, `picked: true`를 붙인다.
- `PlacesSection.tsx`:
  - 고른 식당이 이미 `dateCourses`에 있으면 그 번호로 `initial`을 정하고 코스 영역으로 스크롤만 한다.
  - 아니면 `pending`을 그 id로 두고 요청한다. 성공하면 기존 `picked` 코스를 빼고 새 코스를 맨 앞에 넣은 결과로 상태를 바꾸고, `onLoaded`로 올리고(저장 대상 갱신), `initial = 0`으로 코스를 다시 그리고, 코스 영역으로 부드럽게 스크롤한다(`scrollIntoView({ behavior: 'smooth', block: 'start' })`).
  - 더 늦게 누른 요청만 반영한다(기존 `latest` 카운터와 같은 방식). 새 검색을 시작하면 진행 중인 코스 요청 결과는 버린다.
  - 코스 영역은 `dateCourses`가 하나라도 있으면 보인다(내 코스만 있어도).
- 저장 결과 화면(`features/saved/`)은 바꾸지 않는다. `picked`는 기존 `mapPlaces`의 펼침(`...course`)으로 그대로 저장·표시된다.

## 5. 테스트

백엔드(`./gradlew test`):
- `PlaceServiceTest`:
  - `course`가 고른 식당에서 가장 가까운 카페·볼거리로 코스를 만들고, 구간 이름이 고른 식당 이름에서 시작한다.
  - 카페 검색 실패 → 카페 `null`, 식당 → 볼거리 구간 하나.
  - 카페·볼거리 모두 없음 → `legs = []`, `routeUrl = null`.
- `PlaceControllerTest`:
  - 로그인 없이 `GET /api/places/course` 200, JSON에 `cafe`·`sight`·`legs`·`routeUrl`.
  - 빈 `id`, 101자 `name`, `lat=91`, `lng=-181`, `lat` 누락 → 400.
- `SavedControllerTest`:
  - `picked: true` 코스가 저장·조회에서 그대로 남는다. `picked` 없는 예전 결과는 `null`.
  - 코스 5개 → 400. `picked` 코스 2개 → 400.

화면: `npm run build && npm run lint` + 헤드리스 Chrome(가로챈 `/api/*`). 확인 항목:
1. 행을 펼쳐 버튼 → 진행 문구 → 코스 탭 맨 앞 `내 코스`, 선택됨, 지도와 구간.
2. 다른 식당으로 다시 만들면 내 코스가 바뀌고 탭 수는 그대로.
3. 자동 코스가 있는 식당을 고르면 `/api/places/course` 요청 없이 그 탭 선택.
4. 코스 요청 500 → 행 안 실패 문구, 다시 누르면 성공.
5. 자동 코스 0개인 검색에서 내 코스를 만들면 코스 영역이 나타난다.
6. 저장 결과 화면의 표에는 버튼이 없다.

## 6. 범위 밖

- 카페·볼거리를 다른 후보로 바꾸기, 장소 여러 곳을 직접 엮기.
- 내 코스 여러 개.
- 저장 결과·공유 링크 화면에서 코스 만들기.
- 자동 코스와 겹치지 않는 카페·볼거리 고르기.
