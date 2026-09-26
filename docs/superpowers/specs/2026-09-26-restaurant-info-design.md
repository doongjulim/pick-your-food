# Pick Your Food — B. 맛집 정보 설계

- 작성일: 2026-09-26
- 범위: 서브 프로젝트 B (주위 맛집 · 유명한 근교 맛집 · 데이트 코스 · 가격 비교 · 리뷰 비교)
- 선행: 서브 프로젝트 A (`2026-09-23-food-picker-design.md`), 패키지 구조는 `CLAUDE.md`의 package-by-feature 규칙을 따른다

## 1. 목표

A의 결과 화면(랜덤·추천 모두)에서 1순위 음식을 어디서 먹을지 보여준다.

성공 기준:
- 현재 위치 또는 직접 입력한 장소를 기준으로 주위 맛집(1km)과 유명한 근교 맛집(20km)이 나온다.
- 두 목록을 가격대·평점·리뷰 수로 비교·정렬할 수 있고, 한 곳을 펼치면 리뷰 2~3개를 볼 수 있다.
- 근교 1위 맛집에서 출발하는 데이트 코스(식사 → 카페 → 볼거리)가 나온다.
- 외부 API가 실패해도 A의 결과 화면은 그대로 쓸 수 있다.

## 2. 데이터 출처

| 출처 | 용도 |
|------|------|
| Kakao Local API | 장소 → 좌표 변환, 음식점 검색(`FD6`), 카페(`CE7`)·관광명소(`AT4`) 검색 |
| Google Places API (New) Text Search | 평점, 리뷰 수, 가격대(4단계), 리뷰(최대 5개 중 3개 사용), Google 지도 링크 |

- 키: 환경변수 `KAKAO_REST_KEY`, `GOOGLE_PLACES_KEY` → `application.properties`의 `kakao.rest-key=${KAKAO_REST_KEY:}`, `google.places-key=${GOOGLE_PLACES_KEY:}`. 키가 없어도 앱은 뜬다.
- 한계: 한국 식당은 Google 가격 정보가 대부분 4단계 가격대뿐이다. 메뉴별 실제 가격은 다루지 않는다.
- 비용: `reviews` 필드 때문에 Text Search가 Enterprise + Atmosphere 등급으로 과금된다. 검색 1회당 Google 호출 최대 10건, 24시간 캐시로 반복 비용을 줄인다.

## 3. 아키텍처

서버가 요청 한 번에 모든 데이터를 모아 응답한다 (브라우저에 키 노출 없음).

```
[React] --GET /api/places--> [Spring Boot: place 패키지] --> Kakao Local
                                                         --> Google Places (최대 10건 병렬)
```

## 4. 백엔드 (`com.example.pickyourfood.place`)

| 파일 | 역할 |
|------|------|
| `KakaoClient.java` | 키워드 검색, 카테고리 검색. `RestClient` 사용, 헤더 `Authorization: KakaoAK {key}` |
| `GooglePlacesClient.java` | Text Search. 헤더 `X-Goog-Api-Key`, `X-Goog-FieldMask`. `priceLevel` 문자열 → 1~4 |
| `PlaceService.java` | 목록 선정, Google 보강·캐시, 데이트 코스 |
| `PlaceController.java` | `GET /api/places` |
| record들 | `Place`, `Spot`, `Review`, `DateCourse`, `PlacesResponse` 등 |

외부 호출 제한 시간: 연결 2초, 응답 3초. 새 의존성 없음.

### API

`GET /api/places?food=탄탄멘&lat=37.54&lng=127.05` 또는 `GET /api/places?food=탄탄멘&near=성수동`

- `food` 필수. `lat`+`lng` 또는 `near` 중 하나 필수.

응답:

```json
{
  "origin": { "name": "성수동", "lat": 37.54, "lng": 127.05 },
  "nearby": [Place],
  "famous": [Place],
  "dateCourse": { "restaurant": Place, "cafe": Spot | null, "sight": Spot | null } | null
}
```

- `origin.name`: `near`면 입력값, 좌표면 `"현재 위치"`.
- `Place`: `id`(Kakao id), `name`, `address`, `distanceMeters`(기준 위치에서), `kakaoUrl`, `rating`, `reviewCount`, `priceLevel`(1~4), `reviews`, `googleUrl`. Google 정보가 없으면 `rating`·`reviewCount`·`priceLevel`·`googleUrl`은 `null`, `reviews`는 `[]`.
- `Review`: `author`, `rating`, `text`, `when`(예: "2주 전"). 최대 3개.
- `Spot`: `name`, `category`, `address`, `distanceMeters`(식당에서), `kakaoUrl`.

### 처리 규칙

1. **기준 위치**: `near`가 있으면 Kakao 키워드 검색 첫 결과의 좌표. 결과가 없으면 404.
2. **주위 맛집**: Kakao 키워드 검색 `query=food`, `category_group_code=FD6`, 반경 1,000m, `sort=distance`, 상위 5곳.
3. **근교 맛집**: Kakao 키워드 검색 `query=food`, `FD6`, 반경 20,000m, `sort=accuracy`에서 주위 목록과 id가 겹치지 않는 5곳 → Google 보강 → 리뷰 수 내림차순, 같으면 평점 내림차순. Google 정보 없는 곳은 뒤로.
4. **Google 보강** (주위 + 근교, 최대 10건 병렬):
   - Text Search `textQuery = "{식당이름} {주소}"`, `locationBias` = 식당 좌표 반경 200m 원, `languageCode=ko`, 결과 1개.
   - 결과 좌표가 Kakao 좌표에서 200m 넘게 떨어져 있으면 다른 가게로 보고 정보 없음.
   - 결과는 Kakao id 키로 24시간 메모리 캐시(정보 없음 결과도 캐시).
   - 호출 실패나 Google 키 없음 → 해당 식당은 정보 없음 (전체 요청은 성공).
5. **데이트 코스**: 근교 1위 식당 기준 Kakao 카테고리 검색, `sort=distance`.
   - 카페: `CE7`, 반경 500m 중 가장 가까운 곳. 볼거리: `AT4`, 반경 1,000m 중 가장 가까운 곳. 없으면 각각 `null`.
   - 근교 목록이 비면 `dateCourse`는 `null`.

### 에러

| 상황 | 응답 |
|------|------|
| `food` 누락, 위치(`lat`+`lng` / `near`) 누락 | 400 |
| `near` 장소를 찾을 수 없음 | 404 |
| Kakao 키 없음 또는 Kakao 호출 실패 | 502 |
| Google만 실패 | 200, 해당 필드만 정보 없음 |

## 5. 프론트엔드 (`frontend/src/features/places/`)

`App.tsx`가 결과 화면(`status === 'done'`) 아래에 `PlacesSection`을 붙이고 1순위 음식 이름을 넘긴다. 기능끼리는 서로 import하지 않는다.

### 위치 선택

- 제목 "이 메뉴, 어디서 먹지?", `[현재 위치로]` 버튼, 동네·역 이름 입력칸과 `[찾기]` 버튼.
- 위치 권한은 버튼을 눌렀을 때만 요청한다. 거부되면 "위치 권한이 없어요. 동네 이름으로 찾아보세요." 표시 후 입력칸에 포커스.
- 결과 위에 "성수동 기준" / "현재 위치 기준" 라벨.

### 비교표

- 탭 `주위 1km` / `근교 유명 맛집`. 선택 표시는 `layoutId` 스프링 이동.
- 열: 이름·거리 | 가격대 | 평점 | 리뷰 수. 가격대는 `₩₩` + 나머지 단계 흐리게. 숫자는 `font-mono`.
- 열 제목을 누르면 정렬(방향 화살표 표시). 정보 없음은 `—`로 표시하고 정렬 시 항상 뒤.
- 행을 누르면 리뷰 2~3개(별점·내용·시점)와 `카카오맵` / `Google 지도` 링크(새 탭)가 펼쳐진다. opacity·y 전환만 사용(높이 애니메이션 없음).
- `md` 미만: 표 대신 행마다 이름 + 한 줄 요약(`₩₩ · ★4.3 · 리뷰 1,284 · 820m`). 가로 스크롤 없음.

### 데이트 코스

- `01 식사 → 02 카페 → 03 볼거리` 번호와 연결선으로 이은 타임라인. `md` 이상 가로, 미만 세로.
- 단계별 이름, 카테고리, 이전 장소에서의 거리, 카카오맵 링크.
- 못 찾은 단계는 "근처에 카페를 찾지 못했어요" / "근처에 볼거리를 찾지 못했어요"를 흐리게. `dateCourse`가 `null`이면 코스 영역을 숨긴다.

### 상태

| 상태 | 표시 |
|------|------|
| 로딩 | 표 행 모양 스켈레톤 5줄 + 코스 스켈레톤 |
| 빈 목록 | "1km 안에서 {음식} 파는 곳을 못 찾았어요. 근교 탭을 보거나 다른 동네로 찾아보세요." (근교 탭은 "20km 안에서 …") |
| 404 | 입력칸 아래 "'{입력값}'을 찾지 못했어요." |
| 그 밖의 오류 | B 섹션 안에 오류 카드 + "다시 시도". A 결과는 그대로 |
| 늦은 응답 | 요청 번호로 이전 응답 무시 (재검색, 결과 음식 변경 시) |

### 디자인

A와 동일: zinc 바탕 + 강조색 1개, Phosphor 아이콘(`Crosshair`, `MapPin`, `ForkKnife`, `Coffee`, `Mountains`), 이모지 금지, 스프링 모션, 같은 카드 3개 나란히 금지.

## 6. 테스트

백엔드 (`./gradlew test`, 실제 키 불필요):
- `KakaoClientTest` (`MockRestServiceServer`): 요청 파라미터·`KakaoAK` 헤더, 응답 파싱.
- `GooglePlacesClientTest` (`MockRestServiceServer`): API 키·필드 마스크 헤더, 요청 본문, `priceLevel` → 1~4, 리뷰 파싱.
- `PlaceServiceTest` (Mockito로 클라이언트 대체): 근교 정렬(정보 없음 뒤로), 주위와 중복 제외, 200m 초과 매칭 버림, Google 실패 시 정보 없음, 캐시 재사용, 데이트 코스 선택과 `null`.
- `PlaceControllerTest` (MockMvc + `@MockitoBean`): 200, 400(`food`/위치 누락), 404, 502.

프론트엔드: `npm run build` 타입 검사 + headless Chrome 수동 확인(키가 있으면 실제 API, 없으면 `/api/places`를 가로채 준비한 JSON으로 응답). 확인 항목: 위치 거부 → 직접 입력, 탭 전환, 정렬, 리뷰 펼치기, 빈 결과, 오류·다시 시도, 375px 모바일.

## 7. 범위 밖

- 메뉴별 실제 가격, 리뷰 전문·페이지 넘김.
- 코스 여러 개, 경로·이동 시간 안내.
- 결과 저장·공유, 영구 캐시(DB).
