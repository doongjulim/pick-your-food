# Pick Your Food — C1. 데이트 코스 확장 설계

- 작성일: 2026-09-26
- 범위: 서브 프로젝트 C1 (코스 여러 개, 구간별 도보 시간, 카카오맵 경로 링크)
- 선행: 서브 프로젝트 B (`2026-09-26-restaurant-info-design.md`). 이후 C2(결과 저장·공유)가 이 코스 모양을 사용한다.
- 패키지 구조: `CLAUDE.md`의 package-by-feature 규칙. 백엔드 `place`, 프론트엔드 `features/places/` 안에서만 바뀐다.

## 1. 목표

B의 데이트 코스 하나를, 근교 상위 식당마다 하나씩 최대 3개로 늘리고 장소 사이 이동 정보를 보여준다.

성공 기준:
- 근교 맛집 상위 최대 3곳에서 각각 출발하는 식사 → 카페 → 볼거리 코스가 나오고, 탭으로 바꿔 볼 수 있다.
- 각 구간에 도보 예상 시간과 거리가 나오고, 코스 전체를 카카오맵 도보 길찾기로 열 수 있다.
- 새 API 키·새 의존성 없이 동작한다.

## 2. 이동 정보

- 거리: 두 장소 좌표의 직선거리(haversine, 기존 `PlaceService.meters`).
- 도보 시간: `ceil(meters / 67)`분(시속 약 4km), 최소 1분.
- 경로 링크: `https://map.kakao.com/link/by/walk/{이름},{위도},{경도}/{이름},{위도},{경도}[/…]` — 코스의 장소를 순서대로 모두 넣는다. 이름은 퍼센트 인코딩하고, 링크 구분자와 겹치는 `,` `/`는 공백으로 바꾼 뒤 인코딩한다.
- 한계: 직선거리라 실제 보행 경로보다 짧게 나올 수 있다. 화면에 "직선거리 기준 예상 시간이에요"를 적고, 정확한 경로는 링크로 넘긴다.

## 3. 백엔드 (`com.example.pickyourfood.place`)

### 코스 만들기

1. 근교 목록(정렬 후)의 앞에서 최대 3곳이 각 코스의 식당이다. 근교 목록이 비면 코스는 `[]`.
2. 식당마다 Kakao 카테고리 검색 `sort=distance`, `size=3`:
   - 카페: `CE7`, 반경 500m. 볼거리: `AT4`, 반경 1,000m.
3. 후보 3개 중 앞 코스들이 이미 쓴 장소(Kakao id)가 아닌 가장 가까운 곳을 고른다. 3개 모두 이미 쓰였으면 가장 가까운 곳을 그대로 쓴다. 후보가 없으면 `null`.
4. 코스 검색(식당 3곳 × 2종)은 병렬로 호출하고, 중복 제외는 결과가 모인 뒤 코스 순서대로 적용한다.
5. 카페·볼거리 검색이 실패하면(502 포함 모든 예외) 경고 로그를 남기고 그 단계만 `null`. 목록 검색 실패는 지금처럼 502.
6. 구간(`legs`)은 실제로 이어지는 장소 사이만: 식당 → 카페 → 볼거리. 카페가 `null`이면 식당 → 볼거리. 장소가 식당뿐이면 `legs = []`, `routeUrl = null`.

### API 변경

`GET /api/places` 응답에서 `dateCourse`를 `dateCourses`로 바꾼다(프론트엔드와 함께 배포하므로 호환 필드는 두지 않는다).

```json
{
  "origin": Origin,
  "nearby": [Place],
  "famous": [Place],
  "dateCourses": [
    {
      "restaurant": Place,
      "cafe": Spot | null,
      "sight": Spot | null,
      "legs": [ { "from": "성수탄탄", "to": "어니언 성수", "meters": 320, "walkMinutes": 5 } ],
      "routeUrl": "https://map.kakao.com/link/by/walk/…" | null
    }
  ]
}
```

- `Spot`: `id`, `name`, `category`, `address`, `lat`, `lng`, `kakaoUrl`. `distanceMeters`는 없앤다(구간이 대신 담는다).
- `Leg`: `from`, `to`(장소 이름), `meters`, `walkMinutes`.
- `dateCourses`: 0~3개. 순서는 근교 순위 순서.

### 비용

- Kakao 호출: 요청당 목록 2회 + 코스 최대 6회(지금은 2회). Google 호출은 변화 없음(최대 10건).

## 4. 프론트엔드 (`frontend/src/features/places/`)

- `api.ts`: `Spot`, `Leg`, `DateCourse` 타입 갱신, `Places.dateCourses: DateCourse[]`.
- `DateCourse.tsx` → 코스 목록을 받는 `DateCourses`:
  - 제목 영역: "데이트 코스" 라벨, "{식당}에서 시작해요"(선택한 코스를 따라 바뀜).
  - 코스 탭: `01 성수탄탄` / `02 담담` / `03 옛날탄탄`. 선택 표시는 `layoutId` 스프링 이동(비교표 탭과 다른 id). 코스가 1개면 탭을 숨긴다. 좁은 화면에서는 줄바꿈(가로 스크롤 없음).
  - 탭 전환: `AnimatePresence`로 opacity·y 전환, 단계 등장 순차 애니메이션 유지.
  - 타임라인: `md` 이상 가로, 미만 세로(지금과 동일). 두 번째·세 번째 단계 위에 들어오는 구간 `도보 5분 · 320m`(`PersonSimpleWalk` 아이콘, `font-mono`). 카페가 없으면 볼거리 단계의 구간은 `식당에서 도보 12분 · 780m`.
  - 못 찾은 단계: "근처에 카페를 찾지 못했어요" / "근처에 볼거리를 찾지 못했어요"(지금과 동일).
  - 타임라인 아래 `전체 경로 보기` 버튼(`MapTrifold`, 새 탭) + "직선거리 기준 예상 시간이에요". `routeUrl`이 없으면 버튼과 문구를 숨긴다.
  - `dateCourses`가 비면 코스 영역 전체를 숨긴다.
- `PlacesSection.tsx`: `dateCourse` 대신 `dateCourses`를 넘긴다. 새 검색 결과가 오면 선택 코스는 첫 번째로 돌아간다.
- 디자인: A·B와 동일(zinc + 강조색 1개, Phosphor 아이콘, 이모지 금지, 스프링 모션, 같은 카드 3개 나란히 금지).

## 5. 테스트

백엔드 (`./gradlew test`, 실제 키 불필요):
- `KakaoClientTest`: 카테고리 검색이 `size=3`으로 요청하고 여러 후보를 돌려준다.
- `PlaceServiceTest`:
  - 근교 상위 3곳에서 코스 3개, 근교 2곳이면 2개, 0곳이면 `[]`.
  - 앞 코스가 쓴 카페를 건너뛴다. 후보가 모두 겹치면 가장 가까운 곳을 쓴다.
  - 구간 거리·도보 시간(올림, 최소 1분). 카페가 없으면 식당 → 볼거리 구간 하나.
  - 식당뿐인 코스는 `legs = []`, `routeUrl = null`.
  - 카페 검색이 예외를 던져도 요청은 성공하고 그 코스의 카페만 `null`.
  - `routeUrl`: 장소 순서, 좌표, 한글 이름 인코딩, `,` `/` 치환.
- `PlaceControllerTest`: 응답 JSON에 `dateCourses` 배열.

프론트엔드: `npm run build` 타입 검사 + headless Chrome(가로챈 `/api/*`에 준비한 JSON으로 응답). 확인 항목: 코스 탭 전환과 제목 변경, 구간 표시, 카페 없는 코스의 "식당에서" 구간, 코스 1개일 때 탭 숨김, 경로 링크, 코스 없음, 375px 모바일 가로 스크롤 없음.

## 6. 범위 밖

- 실제 보행·대중교통 경로와 시간(TMAP, Kakao Mobility).
- 사용자가 표에서 고른 식당으로 코스 만들기.
- 코스 저장·공유 — C2에서 다룬다.
