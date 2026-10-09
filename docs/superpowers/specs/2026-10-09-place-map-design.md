# Pick Your Food — H2. 맛집 표를 지도 핀으로 보기 설계

- 작성일: 2026-10-09
- 범위: 서브 프로젝트 H2 (맛집 표의 식당을 지도 핀으로 보여 주고, 핀과 표의 행을 잇기)
- 선행: C2a(맛집 표), G2(코스 지도와 카카오 지도 SDK 로더), C2b(저장·공유).
- 패키지 구조: `CLAUDE.md`의 package-by-feature 규칙. 프론트엔드의 `shared/places/`(새 `PlaceMap`, `PlaceTable`)와 표를 그리는 두 곳(`features/places/PlacesSection.tsx`, `features/saved/SavedView.tsx`)이 바뀐다. 백엔드와 저장 형식은 바뀌지 않는다(식당마다 좌표가 이미 응답에 있다).

## 1. 목표

지금 맛집 표는 목록뿐이라 식당들이 어디에 모여 있는지 알기 어렵다. 표의 지금 탭 식당들을 카카오 지도에 핀으로 보여 주고, 핀과 표의 행을 양방향으로 잇는다.

성공 기준:
- 표의 탭 아래에 지도가 있고, 지금 탭의 식당마다 핀이 하나씩 있다.
- 핀을 누르면 그 행이 펼쳐지고 화면에 보이게 스크롤된다. 펼친 행의 핀을 다시 누르면 접힌다.
- 표에서 행을 펼치면 그 식당의 핀이 강조된다.
- 지도 키가 없거나 SDK를 못 불러오면 지도 없이 지금처럼 표만 보인다.

## 2. 결정 사항

| 항목 | 결정 |
|------|------|
| 위치 | 탭 바로 아래, 표 위에 항상 보인다 |
| 연결 | 양방향. 표와 지도가 `PlaceTable`의 `open`(펼친 행의 식당 id) 하나를 같이 쓴다 |
| 핀 | 평소엔 회색 작은 점. 펼친 행의 식당만 강조색으로 커지고 이름 라벨이 붙는다(이름이 겹치지 않게) |
| 화면 범위 | 탭이 바뀔 때(핀 묶음이 바뀔 때)만 모든 핀이 보이게 맞춘다. 행을 펼치거나 정렬을 바꿀 때는 지도를 움직이지 않는다 |
| 저장 결과·공유 화면 | 같은 표를 쓰므로 지도도 보인다(보기 전용 기능) |
| 지도가 없을 때 | `loadKakaoMaps()`가 `null`이면 지도 칸 없이 표만 |

## 3. 화면

### `shared/places/PlaceMap.tsx` (새 파일)

- props: `places: Place[]`, `origin: { lat: number; lng: number }`, `open: string | null`, `onPick: (id: string) => void`.
- 지도: `loadKakaoMaps()`로 SDK를 받고, `CourseMap`과 같은 칸 모양(`h-60 md:h-80`, `rounded-3xl`, 테두리, 로딩 중 `animate-pulse`, `aria-label="맛집 지도"`). 드래그 가능, 스크롤 휠 확대는 끈다(페이지 스크롤을 뺏지 않게), 확대·축소 버튼(`ZoomControl`).
- 화면 범위: `places`가 바뀔 때 `LatLngBounds`에 모든 식당과 `origin`을 넣고 `setBounds`.
- 검색 위치: `origin`에 흰 테두리의 작은 진회색 점(`CustomOverlay`, 누를 수 없음).
- 핀: 식당마다 `CustomOverlay`의 `content`로 `<button type="button" aria-label="{식당 이름}">`.
  - 평소: 회색 점(`size-3 rounded-full bg-zinc-500 ring-2 ring-white`).
  - 펼친 행(`id === open`): 강조색 점(`size-4 bg-accent`) + 오른쪽에 이름 라벨(흰 배경, 작은 글씨, 한 줄, 최대 너비에서 말줄임), 다른 핀보다 위(`zIndex`).
  - 누르면 `onPick(place.id)`. 핀으로 행을 펼치면 포커스가 그 행의 버튼으로 옮겨 간다(핀은 다시 그려지므로 키보드 사용자가 길을 잃지 않게).
- `open`이 바뀌면 핀 오버레이를 지우고 새 모양으로 다시 올린다(식당 수십 곳이라 충분히 가볍다). 지도 위치는 그대로. 지도 만들기·범위 맞추기는 `places`가 바뀔 때만 한다.

### `kakaoMap.ts`

- `CustomOverlay` 옵션 타입에 `zIndex?: number`와 `clickable?: boolean`(둘 다 SDK에 있는 옵션)만 더한다. 핀은 `clickable: true`로 올려 누를 때 지도 드래그·클릭으로 새지 않게 한다. 나머지(`Map`, `LatLng`, `LatLngBounds`, `ZoomControl`)는 지금 타입 그대로 쓴다.

### `shared/places/PlaceTable.tsx`

- prop `origin: { lat: number; lng: number }`을 더한다.
- 탭과 표 사이(식당이 있을 때만)에 `<PlaceMap places={places} origin={origin} open={open} onPick={pick} />`.
- `pick(id)`: `open === id`면 접고, 아니면 펼치고 그 행을 화면에 보이게 스크롤(`scrollIntoView({ block: 'nearest', behavior: 'smooth' })`). 행은 `li`에 `id` 기반 ref나 `data-place-id`로 찾는다.
- `PlaceMap`에는 정렬한 배열이 아니라 탭의 원래 배열(`nearby` 또는 `famous` prop)을 넘긴다. 정렬한 배열은 렌더마다 새로 만들어져, 넘기면 행을 펼칠 때마다 지도 범위가 다시 맞춰진다.

### 표를 그리는 곳

- `features/places/PlacesSection.tsx`: `origin={state.data.origin}`.
- `features/saved/SavedView.tsx`: `origin={places.origin}`.

## 4. 테스트

`npm run build && npm run lint` + 헤드리스 Chrome(가로챈 `/api/*`, 가짜 `kakao.maps`). 확인 항목:
1. 지금 탭의 식당 수만큼 핀(`aria-label`이 식당 이름인 버튼)이 있다.
2. 핀을 누르면 그 행이 펼쳐지고(`aria-expanded="true"`) 화면 안에 보인다.
3. 표에서 행을 펼치면 그 핀이 강조된다(이름 라벨이 보인다). 다른 행을 펼치면 강조가 옮겨 간다.
4. 펼친 행의 핀을 다시 누르면 행이 접힌다.
5. 탭을 바꾸면 핀이 새 탭의 식당으로 바뀌고 펼친 행은 닫힌다.
6. 지도 키가 없으면(`/api/places/map-key`가 `{ "key": null }`) 지도 칸 없이 표만 나온다.
7. 375px에서 가로로 넘치지 않는다.
8. 저장 결과 화면(`/s/{id}`)에도 지도와 핀이 나온다.

## 5. 범위 밖

- 지도에서 정렬·필터하기, 가까운 핀 묶기(클러스터).
- 지도에서 바로 `이 식당으로 코스 만들기`.
- 지도를 움직여 그 자리에서 다시 검색하기.
