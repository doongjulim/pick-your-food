# Pick Your Food — H4. 식당까지 대중교통 길찾기 링크 설계

- 작성일: 2026-10-12
- 범위: 서브 프로젝트 H4 (검색 위치에서 식당까지 대중교통 경로를 카카오맵으로 열기)
- 선행: C2a(맛집 표), C2b(저장·공유), H2(맛집 지도).
- 패키지 구조: `CLAUDE.md`의 package-by-feature 규칙. 프론트엔드의 `shared/places/PlaceTable.tsx`만 바뀐다. `PlacesSection.tsx`, `SavedView.tsx`는 이미 이름이 든 `origin`을 넘기므로 바뀌지 않는다. 백엔드와 저장 형식은 바뀌지 않는다.

## 1. 목표

맛집 표의 식당까지 대중교통으로 어떻게 가는지 바로 볼 수 있게 한다.

성공 기준:
- 맛집 표에서 행을 펼치면 `대중교통 길찾기` 버튼이 있고, 누르면 새 탭에서 카카오맵이 검색 위치 → 그 식당의 대중교통 경로로 열린다.
- 저장·공유 결과 화면에도 같은 버튼이 있다(저장할 때의 검색 위치에서 출발).

## 2. 결정 사항

| 항목 | 결정 |
|------|------|
| 방법 | 카카오맵 길찾기 링크. 대중교통 경로 API(TMAP 하루 10건, ODsay 하루 30건 무료)는 한도가 너무 작아 쓰지 않는다 |
| 링크 | `https://map.kakao.com/link/by/traffic/{출발 이름},{위도},{경도}/{식당 이름},{위도},{경도}` (`traffic`은 경유지를 받지 않는다) |
| 출발지 | 검색 기준 위치 `origin`. 이름은 `origin.name`(동네 이름 또는 `현재 위치`) |
| 이름 처리 | `,`와 `/`를 공백으로 바꾸고 `encodeURIComponent`. 백엔드 도보 코스 링크(`PlaceService`)와 같은 규칙 |
| 위치 | 펼친 행의 버튼 줄, `카카오맵`·`Google 지도` 다음 |
| 앱 안 소요 시간 | 보이지 않는다(카카오맵에서 본다) |

## 3. 화면 (`shared/places/PlaceTable.tsx`)

- prop `origin`의 타입을 `{ name: string; lat: number; lng: number }`로 넓힌다(`Places['origin']`와 같다).
- 링크를 만드는 작은 함수(같은 파일):
  - `transitUrl(origin, place)` → `TRANSIT_BASE + point(origin) + '/' + point(place)`.
  - `point({ name, lat, lng })` → `` `${encodeURIComponent(name.replace(/[,/]/g, ' '))},${lat},${lng}` ``.
- `Reviews`의 버튼 줄에 `<MapLink href={transitUrl(origin, place)} label="대중교통 길찾기" />`. `Reviews`는 `origin`을 prop으로 받는다.
- 이 링크는 API를 부르지 않으므로 키·로딩·실패 상태가 없다.

## 4. 테스트

`npm run build && npm run lint` + 헤드리스 Chrome(가로챈 `/api/*`). 확인 항목:
1. 동네 이름으로 찾고 행을 펼치면 `대중교통 길찾기` 링크의 `href`가 기대한 URL과 같다. 이름에 `,`나 `/`가 든 식당도 확인한다.
2. 현재 위치로 찾으면 출발 이름이 `현재 위치`(인코딩된 값)다.
3. 링크는 `target="_blank"`, `rel="noreferrer"`다.
4. 저장 결과 화면(`/s/{id}`)에서도 버튼이 나오고 출발지는 저장된 `origin`이다.
5. 375px에서 펼친 행이 가로로 넘치지 않는다.

## 5. 범위 밖

- 앱 안에서 소요 시간·환승 보여 주기(대중교통 API 필요).
- 데이트 코스 단계마다 대중교통 링크.
- 출발지를 따로 고르기(카카오맵에서 바꿀 수 있다).
