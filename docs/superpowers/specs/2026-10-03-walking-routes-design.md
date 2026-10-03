# Pick Your Food — G2. 실제 도보 경로와 코스 지도 설계

- 작성일: 2026-10-03
- 범위: 서브 프로젝트 G2 (데이트 코스 구간의 실제 보행 거리·시간, 화면 안 지도에 경로 그리기)
- 선행: C1(데이트 코스, 직선거리 도보 시간), C2b(저장·공유), D(배포).
- 패키지 구조: `CLAUDE.md`의 package-by-feature 규칙. 백엔드는 `place`(경로 계산)와 `saved`(저장 형식), 프론트엔드는 `shared/places/`(두 기능이 함께 쓰는 코스 화면)만 바뀐다.

## 1. 목표

데이트 코스의 구간(식당 → 카페 → 볼거리)에 실제 보행 경로 기준 거리·시간을 보여주고, 선택한 코스를 화면 안 지도에 번호 핀과 경로 선으로 그린다. 저장한 결과와 공유 링크도 같은 지도를 보여준다.

성공 기준:
- `TMAP_APP_KEY`가 있으면 구간의 `meters`·`walkMinutes`가 TMAP 보행자 경로 값이고 `path`에 경로 좌표가 있다.
- TMAP 호출이 실패하거나 키가 없으면 검색은 성공하고 그 구간만 지금처럼 직선거리 추정(`path = null`)이다.
- `KAKAO_JS_KEY`가 있으면 코스 아래에 지도가 보이고, 없거나 SDK 로딩이 실패하면 지도 영역만 사라진다.
- 경로 좌표를 담은 결과를 저장·공유할 수 있고, 예전에 저장한 결과(`path` 없음)도 그대로 열린다.

## 2. 결정 사항

| 항목 | 결정 |
|------|------|
| 범위 | 코스 안 구간의 도보 경로만. 출발지 → 식당 대중교통은 하지 않는다 |
| 경로 API | TMAP 보행자 경로(`POST https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1`). 카카오 모빌리티·Google은 한국 도보 길찾기를 제공하지 않는다 |
| 호출 방식 | 서버가 구간마다 한 번(검색당 최대 6회, 병렬). 경유지 한 번 호출은 응답을 구간별로 나누기 어려워 쓰지 않는다. 브라우저 직접 호출은 키 노출로 제외 |
| 실패 | 구간 단위로 직선거리 추정으로 돌아간다(경고 로그). 무료 사용량 초과도 같다 |
| 지도 | 카카오맵 JS SDK. 새 npm 의존성 없이 `<script>`로 불러온다 |
| JS 키 전달 | 서버가 실행 중에 `GET /api/places/map-key`로 알려준다. 빌드 인자 없음 |
| 저장 | 경로 좌표도 저장한다. 소수 5자리, 구간당 최대 200개 |

## 3. 백엔드

### `place/TmapClient` (새 파일)

- 요청: `POST https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1`
  - 헤더 `appKey: {TMAP 키}`, `Content-Type: application/json`
  - 본문 `{"startX": 출발 경도, "startY": 출발 위도, "endX": 도착 경도, "endY": 도착 위도, "startName": 출발 이름, "endName": 도착 이름, "reqCoordType": "WGS84GEO", "resCoordType": "WGS84GEO"}`
- 응답(GeoJSON `FeatureCollection`):
  - 총거리·총시간: 첫 feature의 `properties.totalDistance`(m), `properties.totalTime`(초).
  - 경로: `geometry.type`이 `LineString`인 feature의 `coordinates`(`[경도, 위도]`)를 순서대로 잇는다. 앞 선의 끝점과 같은 시작점은 한 번만 넣는다. 결과는 `[위도, 경도]` 목록.
- 반환: `Route(int meters, int seconds, List<double[]> path)`.
- 그 밖의 HTTP 오류·네트워크 오류·해석할 수 없는 응답은 `RestClientException`(해석 실패도 `RestClientException`으로 감싼다).
- 키가 비어 있으면 요청을 보내지 않고 `Optional.empty()`를 돌려준다(`Optional<Route> route(...)`).
- `RestClient` + `food.HttpTimeouts.factory()`. 테스트용 생성자 `TmapClient(RestClient.Builder, String key)`는 `KakaoClient`와 같은 모양.

### 구간 계산 (`PlaceService`)

1. 코스의 장소(식당·카페·볼거리)가 정해진 뒤, 모든 코스의 구간을 모아 TMAP을 병렬로 부른다(기존 코스 검색과 같은 실행기).
2. 성공: `meters = totalDistance`, `walkMinutes = max(1, ceil(seconds / 60))`, `path` = 줄인 좌표.
3. 실패(예외) 또는 키 없음: 경고 로그(예외일 때만), 지금 계산(직선거리, 분속 `WALK_METERS_PER_MINUTE` 67m, 올림, 최소 1분), `path = null`.
4. 좌표 줄이기: 각 좌표를 소수 5자리로 반올림. 200개를 넘으면 첫 점과 끝 점을 남기고 고르게 골라 200개로 만든다.
5. 캐시: 메모리 `ConcurrentHashMap`, 키 `"{출발 id}>{도착 id}"`, 24시간(기존 `CACHE_TTL`). 성공한 경로만 캐시한다(실패는 다음 검색에서 다시 묻는다).
6. `routeUrl`(카카오맵 도보 길찾기 링크)은 그대로.

### 응답 모양

`PlacesResponse.Leg`에 `path`를 더한다.

```json
{ "from": "성수탄탄", "to": "어니언 성수", "meters": 412, "walkMinutes": 6,
  "path": [[37.54421, 127.05589], [37.54433, 127.05612]] }
```

`path`는 `null`일 수 있다(직선거리 추정).

### `GET /api/places/map-key`

- `{ "key": "…" }`, 키가 비어 있으면 `{ "key": null }`. 로그인 불필요.
- 값: `kakao.js-key=${KAKAO_JS_KEY:}`. JavaScript 키는 카카오 콘솔에서 웹 도메인으로 제한되는 공개 키라 응답에 실어도 된다.

### 저장 결과 (`saved`)

- `SavedResult.Leg`에 `List<double[]> path`(null 허용)를 더한다. 예전 결과는 `path`가 없어 `null`로 읽힌다.
- 검증(`SavedController.valid`): `path`가 있으면 200개 이하, 각 점은 숫자 2개, 위도 -90~90, 경도 -180~180. 어기면 400.

### 설정·배포·문서

- `application.properties`: `tmap.app-key=${TMAP_APP_KEY:}`, `kakao.js-key=${KAKAO_JS_KEY:}`
- `render.yaml`: `TMAP_APP_KEY`, `KAKAO_JS_KEY` (`sync: false`)
- `README.md` 키 목록과 배포 절차:
  - `TMAP_APP_KEY`: [SK open API](https://openapi.sk.com)에서 TMAP 앱을 만들고 보행자 경로 상품을 사용 신청한 뒤 앱 키. 없으면 직선거리 추정.
  - `KAKAO_JS_KEY`: 카카오 개발자 콘솔 → 앱 키 → JavaScript 키. 앱 → 플랫폼 → Web에 사이트 도메인(`http://localhost:5173`, `http://localhost:8080`, `https://<서비스 이름>.onrender.com`)을 등록해야 지도가 열린다. 없으면 지도를 숨긴다.

### 비용

- TMAP: 검색당 최대 6회, 구간 캐시 24시간. 무료 사용량은 SK open API 콘솔에서 확인하고, 넘으면 직선거리 추정으로 동작한다.
- 저장 결과 크기: 구간 6개 × 200점 × 약 22바이트 ≈ 26KB. 기존 `payload` 한도 64KB 안.

## 4. 화면

### 타입

- `shared/places/types.ts`: `Leg`에 `path: [number, number][] | null`.
- `features/saved/api.ts`: 코드 변경 없음. 저장 타입이 `shared/places/types.ts`의 `DateCourse`에서 나오고 `mapPlaces`가 구간을 그대로 넘기므로 `path`가 저장 요청에 함께 실린다.

### SDK 로딩 (`shared/places/kakaoMap.ts`, 새 파일)

- `loadKakaoMaps(): Promise<KakaoMaps | null>`: 처음 호출 때 `GET /api/places/map-key` → 키가 있으면 `<script src="https://dapi.kakao.com/v2/maps/sdk.js?appkey={key}&autoload=false">`를 한 번 넣고 `kakao.maps.load` 완료를 기다린다. 결과 Promise를 모듈 변수에 두고 재사용한다.
- 키 없음·스크립트 실패·`load` 실패 → `null`.
- 타입은 쓰는 API(`Map`, `LatLng`, `LatLngBounds`, `Polyline`, `CustomOverlay`)만 작은 선언으로 둔다.

### 지도 (`shared/places/CourseMap.tsx`, 새 파일)

- `DateCourses`의 타임라인과 `전체 경로 보기` 버튼 사이, 선택한 코스 하나.
- 크기: 높이 240px, `md` 이상 320px, `rounded-3xl`, zinc 테두리, `overflow-hidden`.
- 핀: 장소 순서 번호(1 식당, 2 카페, 3 볼거리; 없는 단계는 건너뛰고 번호는 나타난 순서). 강조색 원 + 흰 숫자의 `CustomOverlay`.
- 선: 강조색. `path`가 있으면 그 좌표의 실선, `null`이면 두 장소를 잇는 점선(`strokeStyle: 'shortdash'`).
- 범위: 모든 핀과 경로 좌표가 들어가도록 `setBounds`.
- 코스 탭을 바꾸면 이전 핀·선을 지우고 다시 그린다. 지도 객체는 한 번만 만든다.
- 조작: `setDraggable(false)`(한 손가락 페이지 스크롤이 지도에 걸리지 않게), 확대·축소 컨트롤은 둔다.
- `loadKakaoMaps()`가 `null`이면 아무것도 그리지 않는다(영역도 없음). 로딩 중에는 같은 크기의 회색 자리표시.

### 문구 (`DateCourses.tsx`)

- 구간 표시 `도보 5분 · 320m`는 그대로.
- 안내 문구: 모든 구간에 `path`가 있으면 숨김, 하나라도 `null`이면 `일부 구간은 직선거리 기준이에요`, 모두 `null`이면 지금 문구 `직선거리 기준 예상 시간이에요`.

### 다른 화면

검색 화면(`features/places`)과 저장·공유 화면(`features/saved`)이 같은 `DateCourses`를 쓰므로 두 곳 모두 지도가 생긴다. 그 밖의 화면은 바꾸지 않는다.

## 5. 테스트

백엔드(`./gradlew test`, 실제 키 불필요):
- `TmapClientTest`(`MockRestServiceServer`):
  - 요청: `POST /tmap/routes/pedestrian?version=1`, `appKey: test-tmap`, 본문의 좌표·이름·`WGS84GEO`.
  - 응답: Point·LineString이 섞인 준비한 응답에서 거리·시간, LineString 좌표가 순서대로 이어지고 겹치는 이음점이 한 번만 들어간다(`[위도, 경도]`).
  - 500 → `RestClientException`. 빈 키 → 요청 없음, `Optional.empty()`.
- `PlaceServiceTest`:
  - TMAP 성공 → 구간 값이 TMAP 값(`ceil(초/60)`, 최소 1분), `path` 있음.
  - TMAP 예외 → 요청 성공, 그 구간만 직선거리 값과 `path = null`.
  - 1,000점 경로 → 200점 이하, 첫·끝 점 유지, 소수 5자리.
  - 같은 구간을 두 번 검색 → TMAP 호출 한 번.
- `PlaceControllerTest`: `legs[].path` 직렬화. `GET /api/places/map-key` → 키 값 / 빈 키면 `null`.
- `SavedControllerTest`: `path` 있는 결과 저장·조회 왕복, `path` 없는 결과 조회, 201점·범위 밖·숫자 3개짜리 점 → 400.
- 기존 테스트 모두 통과.

화면(로컬 headless Chrome, 가로챈 `/api/*`에 준비한 JSON):
- 실제 카카오 SDK는 도메인 등록이 필요하므로 `dapi.kakao.com` 요청을 가로채 작은 가짜 `kakao.maps`(호출 기록)를 돌려준다.
- 확인: 지도 영역 표시, 핀 3개·선 그리기 호출(실선·점선 구분), 탭 전환 시 다시 그림, `key: null`이면 지도 영역 없음, 안내 문구 세 경우, 저장 화면에서도 지도, 375px 가로 스크롤 없음.

배포 후(사용자): Render에 `TMAP_APP_KEY`·`KAKAO_JS_KEY` 입력, 카카오 콘솔에 Web 도메인 등록 → 실제 검색에서 지도와 경로 선, 구간 시간이 카카오맵 도보 길찾기와 비슷한지 확인.

## 6. 범위 밖

- 출발지 → 식당 대중교통·자동차 경로.
- 지도에서 장소를 눌러 정보 보기, 지도 끌어서 다시 검색.
- 맛집 표의 식당들을 지도에 표시.
- 여러 코스를 한 지도에 겹쳐 보기.
- 경로 결과의 DB 캐시.
