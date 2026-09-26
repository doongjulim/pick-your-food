# Pick Your Food — C2b. 결과 저장·기록·공유 설계

- 작성일: 2026-09-26
- 범위: 서브 프로젝트 C2b (결과 저장, 내 저장 목록, 공유 링크, 로그인 후 이어서 저장)
- 선행: C2a 카카오 로그인(`2026-09-26-login-design.md`) — 계정, 세션, CSRF, 비로그인 `401` 규칙을 그대로 쓴다.
- 패키지 구조: `CLAUDE.md`의 package-by-feature 규칙. 새 기능 `saved`(백엔드 `com.example.pickyourfood.saved`, 프론트엔드 `features/saved/`).

## 1. 목표

지금 본 결과(메뉴 + 찾아본 맛집·데이트 코스)를 저장해 나중에 다시 보고, 링크로 다른 사람에게 보낸다.

성공 기준:
- 결과 화면에서 `저장하기` → `저장했어요` + `링크 공유`. 링크를 받은 사람은 로그인 없이 보낸 사람과 같은 메뉴·맛집·코스를 본다.
- 로그인 전에 저장을 누르면 카카오 로그인을 거쳐 돌아온 뒤 그 결과가 자동으로 저장된다.
- 계정 메뉴의 `저장한 결과`에서 내 저장 목록을 보고, 열고, 지운다. 지운 결과의 링크는 더 이상 열리지 않는다.
- 새 API 키·새 의존성 없이 동작하고, 기존 테스트는 그대로 통과한다.

## 2. 결정 사항

| 항목 | 결정 |
|------|------|
| 저장 내용 | 메뉴(추천 + 대안) + 그때 찾은 맛집(근교·유명)과 데이트 코스 통째로. 맛집을 찾기 전이면 메뉴만 |
| Google 정보 | 저장하지 않는다(평점·리뷰 수·가격대·리뷰·Google 링크, Google 약관). 식당 이름·주소·거리·좌표·카카오맵 링크·코스(구간, 경로 링크)와 저장 당시 순서는 저장 |
| 로그인 전 저장 | 결과를 `sessionStorage`에 보관 → 카카오 로그인 → 돌아오면 자동 저장. 로그인 실패면 보관분 폐기 |
| 공유 | 저장 결과마다 추측할 수 없는 링크 하나(`/s/{id}`), 누구나 로그인 없이 본다. 지우면 링크도 사라진다. 공개/비공개 전환 없음 |
| 저장 방식 | 결과 1건 = 1행 스냅샷(JSON). 서버가 정해진 타입으로 읽고 다시 써서 Google·모르는 필드를 버린다. 수정 기능 없음 |

## 3. 백엔드 (`com.example.pickyourfood.saved`)

| 파일 | 역할 |
|------|------|
| `SavedController` | API 4개 |
| `SavedRepository` | `JdbcClient`로 `saved_result` 읽기·쓰기 |
| `SavedResult` 등 record | 저장 결과의 모양(Google 필드 없음). `place` 기능의 타입은 import하지 않고 이 기능 안에 둔다 |

- 로그인한 계정 id: `Authentication.getName()`(C2a에서 principal 이름 속성을 `accountId`로 정했다). `account` 기능을 import하지 않는다.
- `SecurityConfig`(account): `GET /api/saved/*`만 누구나. 나머지 `/api/saved`는 기존 규칙대로 로그인 필요(`401`).

### 저장 모양 (`payload`)

```json
{
  "title": "오늘의 랜덤 메뉴",
  "best": { "id": "tantanmen", "name": "탄탄멘", "description": "…" },
  "alternatives": [Food],
  "places": {
    "origin": { "name": "성수동", "lat": 37.54, "lng": 127.05 },
    "nearby": [Place],
    "famous": [Place],
    "dateCourses": [DateCourse]
  } | null
}
```

- `Place`: `id`, `name`, `address`, `distanceMeters`, `lat`, `lng`, `kakaoUrl`. (`GET /api/places`의 `Place`에서 Google 필드를 뺀 것)
- `Spot`, `Leg`, `DateCourse`: C1과 같은 필드. `DateCourse.restaurant`는 위 `Place`.
- 응답에서 저장 결과를 돌려줄 때 `Place`의 `rating`·`reviewCount`·`priceLevel`·`googleUrl`은 `null`, `reviews`는 `[]`로 채워 기존 화면 타입과 맞춘다.

### DB (`V3__saved_result.sql`)

```
saved_result(
  id varchar(22) primary key,
  account_id bigint not null references account(id),
  title varchar(100) not null,
  food_name varchar(100) not null,
  origin_name varchar(200),
  payload varchar(65536) not null,
  created_at timestamp not null
)
index (account_id, created_at)
```

- `id`: `SecureRandom` 16바이트 → base64url(패딩 없음) 22자.
- 목록용 `title`·`food_name`·`origin_name`은 열로, 전체 모양은 `payload`(JSON 문자열).

### API

| 요청 | 로그인 | 응답 |
|------|--------|------|
| `POST /api/saved` | 필요, CSRF 헤더 필요 | `201 {"id": "…"}` / 형식 오류·상한 초과 `400` |
| `GET /api/saved` | 필요 | `200 [{"id","title","foodName","originName","createdAt"}]` 최신순, 내 것만 |
| `GET /api/saved/{id}` | 없음 | `200 {"id","createdAt","mine","result": payload}` / 없으면 `404` |
| `DELETE /api/saved/{id}` | 필요, CSRF 헤더 필요 | 내 것 `204` / 남의 것·없음 `404`(존재 여부를 알리지 않음) |

요청 검증(`400`):
- `title`(1~100자), `best`(`id`·`name` 필수) 필수.
- 상한: 대안 5개, 근교·유명 각 10곳, 코스 3개, 코스당 구간 2개, 저장할 JSON 64KB.
- 문자열 필드는 100자(주소·URL은 500자) 이하.

범위 밖(나중에): 목록 페이지 나누기, 계정당 저장 개수 상한.

## 4. 프론트엔드

### 공용으로 옮기기

`SavedView`도 맛집 표·데이트 코스를 그려야 하므로 `features/places/`의 `PlaceTable.tsx`, `DateCourses.tsx`, `MapLink.tsx`, `format.ts`와 장소 타입(`Review`, `Place`, `Spot`, `Leg`, `DateCourse`, `Places`)을 `shared/places/`로 옮긴다(내용 변경 없음). `features/places/`에는 `PlacesSection.tsx`와 `fetchPlaces`·`Where`만 남는다. 저장본의 Google 필드는 `null`이라 표는 기존 "Google 정보 없음" 표시를 그대로 쓴다.

### `features/saved/`

- `api.ts`: `saveResult(result): Promise<string>`(id), `fetchSaved(id)`, `fetchMySaved()`, `deleteSaved(id)`, 저장 모양 타입.
- `pending.ts`: 로그인 전 저장 보관(`sessionStorage` 키 `pending-save`) 넣기·꺼내기·지우기.
- `SaveButton.tsx`: 결과 화면 메뉴 아래.
  - `저장하기` → `저장하는 중…` → `저장했어요` + `링크 공유`. 실패 시 "저장하지 못했어요. 다시 시도해 주세요."
  - 보내는 결과에서 Google 필드를 뺀다(서버도 버리지만 보내지 않는다).
  - `401`이면 결과를 보관하고 `/oauth2/authorization/kakao`로 페이지 이동.
  - 맛집을 새로 찾으면(보낼 결과가 바뀌면) `저장하기`로 돌아간다.
- `SavedView.tsx`(`/s/{id}`):
  - "저장한 결과 · 9월 26일", 제목, 메뉴와 대안, 맛집이 있으면 "{위치} 기준" 근교·유명 표와 데이트 코스(읽기 전용).
  - 버튼: `링크 공유`, `처음으로`, 내 것(`mine`)이면 `삭제` → "이 결과를 지울까요? 링크도 더 이상 열리지 않아요." 확인 → 목록으로.
  - 불러오는 중 자리표시, `404`면 "이 결과를 찾을 수 없어요. 삭제되었거나 주소가 잘못됐어요." + `처음으로`, 그 밖의 오류는 다시 시도 버튼.
- `SavedList.tsx`(`/saved`):
  - 행마다 메뉴 이름, 제목, "{위치} 기준"(없으면 생략), 날짜, 삭제 버튼. 행을 누르면 `SavedView`.
  - 비었을 때 "아직 저장한 결과가 없어요. 메뉴를 뽑고 저장해 보세요." + `처음으로`.
  - `401`이면 "로그인하면 저장한 결과를 볼 수 있어요." + `카카오로 로그인` 링크.
- 링크 공유: `navigator.share`가 있으면 공유 창, 없으면 클립보드 복사 후 "링크를 복사했어요". 링크는 `{location.origin}/s/{id}`.

### 로그인 후 이어서 저장

앱 시작 시 보관된 결과가 있으면:
- 주소에 `?login=failed`가 있으면 보관분을 지우고 끝(기존 실패 안내는 `AccountMenu`가 보인다).
- 아니면 저장 요청 → 성공: 보관분 삭제, `/s/{id}`로 이동하고 "저장했어요". `401`(로그인 안 됨)·기타 실패: 보관분 삭제, 처음 화면에 "저장하지 못했어요. 다시 시도해 주세요."

### 수정

- `App.tsx`:
  - 화면 `saved-list`, `saved-view` 추가. 시작 시 주소(`/s/{id}`, `/saved`)로 화면을 고른다.
  - 화면 이동은 `history.pushState`로 주소를 바꾸고, 뒤로가기(`popstate`)에 맞춰 화면을 바꾼다. 라우터 라이브러리는 쓰지 않는다. 처음 화면은 `/`.
  - `PlacesSection`의 새 `onLoaded(places | null)`로 받은 맛집을 현재 결과와 함께 `SaveButton`에 넘긴다.
- `features/places/PlacesSection.tsx`: `onLoaded` prop(검색 완료 시 결과, 새 검색 시작 시 `null`).
- `features/account/AccountMenu.tsx`: 드롭다운 `로그아웃` 위에 `저장한 결과`(`onOpenSaved` prop, App이 넘긴다 — 기능끼리 import하지 않는다).
- `CLAUDE.md`: 기능 목록에 `saved`, 공용 `shared/places/`.
- Vite 개발 서버는 `/s/…`·`/saved`에 `index.html`을 돌려주므로 설정 변경 없음. 운영 배포의 SPA 경로 처리는 범위 밖.

디자인: A·B·C1·C2a와 동일(zinc + 강조색 1개, Phosphor, 스프링 모션, 이모지 금지, 375px 가로 스크롤 없음). 목록은 카드 대신 `divide-y`.

## 5. 테스트

백엔드(`./gradlew test`, 실제 키 불필요, 인메모리 H2 + V3):
- `SavedControllerTest`(MockMvc, `RANDOM_PORT`, 로그인은 C2a처럼 세션 테이블의 실제 세션, CSRF는 실제 `XSRF-TOKEN` 쿠키):
  - 비로그인 저장 `401`, CSRF 없음 `403`, 로그인 + CSRF `201` + 22자 id.
  - Google 필드·모르는 필드를 넣어 저장해도 다시 읽으면 Google 값은 `null`/`[]`이고 모르는 필드는 없다.
  - 맛집 없이 메뉴만 저장.
  - `400`: `best` 없음, 대안 6개, 근교 11곳, 코스 4개, 64KB 초과.
  - 비로그인 `GET /api/saved/{id}` `200` + `mine=false`, 주인 `mine=true`, 없는 id `404`.
  - 목록은 내 것만 최신순.
  - 남의 결과 삭제 `404`(남아 있음), 내 결과 삭제 `204` 후 링크 `404`.
- 기존 테스트 61개 통과.

프론트엔드: `npm run build` + headless Chrome(가로챈 `/api/*`):
- `save`: 로그인 상태로 뽑기 → 맛집 찾기 → 저장. 요청 본문에 Google 필드 없음, `X-XSRF-TOKEN` 있음, `저장했어요` + `링크 공유` → 클립보드에 `/s/{id}`.
- `pending`: 비로그인 저장 → `401` → 보관 + 카카오 주소로 이동. 로그인 상태로 다시 열면 자동 저장 후 `/s/{id}`에 "저장했어요".
- `pending-failed`: 보관분 + `?login=failed` → 보관분 삭제, 저장 요청 없음.
- `view`: 비로그인 `/s/{id}` → 메뉴, "{위치} 기준" 표, 코스 탭, 삭제 버튼 없음. 없는 id 안내.
- `mine`: 내 결과 삭제 → 확인 → 목록.
- `list`: 계정 메뉴 → `저장한 결과` → 행 → 해당 결과 → 뒤로가기로 목록. 빈 목록 안내.
- `mobile`: 375px에서 결과·목록 가로 스크롤 없음.
- C1 데이트 코스 브라우저 확인(`courses`, `single`, `none`, `mobile`)을 다시 돌려 공용 이동으로 깨진 곳이 없는지 본다.

실제 카카오 로그인을 거친 저장은 키 설정 후 사용자가 직접 확인한다.

## 6. 범위 밖

- 저장 결과 수정·이름 바꾸기, 공개/비공개 전환, 링크 만료.
- 목록 페이지 나누기·검색, 계정당 저장 상한.
- 공유 링크 미리보기(Open Graph 카드) — 서버가 HTML을 만들지 않으므로 나중에.
- 운영 배포의 SPA 경로 처리.
