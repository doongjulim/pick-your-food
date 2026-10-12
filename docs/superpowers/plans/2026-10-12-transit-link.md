# H4 식당까지 대중교통 길찾기 링크 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 맛집 표의 펼친 행에 `대중교통 길찾기` 버튼을 더해, 검색 위치에서 그 식당까지의 대중교통 경로를 카카오맵으로 연다.

**Architecture:** `shared/places/PlaceTable.tsx` 안에서 `origin`(이름 포함)과 식당 좌표로 카카오맵 `link/by/traffic` URL을 만들고, 기존 `MapLink`로 보여 준다. API 호출·백엔드·저장 형식 변경은 없다.

**Tech Stack:** React 19, Vite, TypeScript, Tailwind v4, oxlint. 확인은 헤드리스 Chrome(CDP)으로.

**Spec:** `docs/superpowers/specs/2026-10-12-transit-link-design.md`

## Global Constraints

- 바뀌는 제품 코드는 `frontend/src/shared/places/PlaceTable.tsx` 하나. 백엔드·`features/*`는 바꾸지 않는다.
- 링크: `https://map.kakao.com/link/by/traffic/{출발 이름},{위도},{경도}/{식당 이름},{위도},{경도}`.
- 이름은 `,`와 `/`를 공백으로 바꾼 뒤 `encodeURIComponent`.
- 버튼 문구: `대중교통 길찾기`. 위치: `카카오맵`·`Google 지도` 다음.
- 이모지 금지.
- `.superpowers/`, `.claude/`, `.serena/`, `graphify-out/`, `data/`는 커밋하지 않는다.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. 식당·동네 이름에 `,` `/` `#` `?` `%` 공백이 들어 있을 때. URL이 깨지지 않고 이름 칸이 하나로 남아야 한다. → 확인 1에서 `,`와 `/`가 든 이름, 확인 1b에서 `#`·`?`·`%`·`&`가 든 이름.
2. 현재 위치로 찾은 경우. 출발 이름은 `현재 위치`여야 한다. → 확인 2.
3. 예전에 저장한 결과(`origin.name`이 있는 저장 형식). 버튼이 나오고 저장된 위치에서 출발해야 한다. → 확인 4.
4. 좁은 화면. 버튼 줄이 넘치지 않고 줄바꿈되어야 한다. → 확인 5.
5. 근교 유명 맛집 탭. 같은 `origin`에서 출발해야 한다. → 확인 1c.

---

### Task 1: 펼친 행에 대중교통 길찾기 링크

**Files:**
- Modify: `frontend/src/shared/places/PlaceTable.tsx`
  - `Props.origin` 타입
  - `<PlaceRow …>` 호출
  - `PlaceRowProps` / `PlaceRow`
  - `Reviews`
- Test (커밋하지 않음): `.superpowers/tools/transit-link.mjs`. CDP 연결과 `/api/*` 가로채기는 `.superpowers/tools/place-map.mjs`에서 복사한다.

**Interfaces:**
- Consumes:
  - `Places['origin']`(`{ name: string; lat: number; lng: number }`, `types.ts`)
  - `MapLink({ href, label })`
  - `PlacesSection.tsx:223`와 `SavedView.tsx:151`는 이미 이 모양의 `origin`을 넘긴다.
- Produces: 없음(파일 내부 함수만).

- [ ] **Step 1: 헤드리스 드라이버를 먼저 쓴다 (RED)**

`.superpowers/tools/transit-link.mjs`를 만든다. 뼈대는 `place-map.mjs`에서 가져온다:
- CDP 연결
- `Fetch.enable`로 `http://localhost:5174/api/*` 가로채기
- 가짜 `navigator.geolocation`(`Page.addScriptToEvaluateOnNewDocument`)
- `/api/me`는 404(로그인 안 함), `/api/places/map-key`는 `{ "key": null }`(지도 없이 표만)

가로챌 응답:
- `/api/places?…near=…`: 다음 응답을 준다.
  ```js
  {
    origin: { name: '성수/뚝섬, 2가', lat: 37.5445, lng: 127.0557 },
    nearby: [placeA],
    famous: [placeB],
    dateCourses: [],
  }
  ```
  - `placeA`: `{ id: 'a1', name: '라멘, 집/본점', lat: 37.5450, lng: 127.0560, … }`
  - `placeB`: `{ id: 'b1', name: '50% 할인 #1 & 국수?', lat: 37.5600, lng: 127.0400, … }`
  - 나머지 필드(`address`, `distanceMeters`, `kakaoUrl`, `rating`, `reviewCount`, `priceLevel`, `reviews: []`, `googleUrl: null`)는 `place-map.mjs`의 식당 모양을 따른다.
- `/api/places?…lat=…&lng=…`(현재 위치): 같은 식당, `origin: { name: '현재 위치', lat: 37.498, lng: 127.027 }`. 백엔드 `PlaceController`가 붙이는 이름과 같다.
- `/api/saved/s1`: 다음 응답을 준다. `result`는 `place-map.mjs`의 저장 결과 모양을 따른다.
  ```js
  {
    id: 's1',
    createdAt: '2026-10-09T03:00:00Z',
    mine: false,
    shared: true,
    result: { …, places: { origin: { name: '강남역', lat: 37.498, lng: 127.0276 }, nearby: [placeA], famous: [], dateCourses: [] } },
  }
  ```

기대 URL은 드라이버 안에서 스펙대로 만든다.

```js
const point = (p) => `${encodeURIComponent(p.name.replace(/[,/]/g, ' '))},${p.lat},${p.lng}`
const expected = (from, to) => `https://map.kakao.com/link/by/traffic/${point(from)}/${point(to)}`
const link = `[...document.querySelectorAll('a')].find((a) => a.textContent.trim() === '대중교통 길찾기')`
```

확인 항목(각각 `PASS`/`FAIL` 한 줄, 하나라도 FAIL이면 종료 코드 1):
1. 홈에서 메뉴 결과까지 간다(`place-map.mjs`와 같은 흐름). 동네 입력에 `성수`를 넣고 `찾기`를 누른 뒤 `라멘, 집/본점` 행을 펼친다.
   - 확인 1: 링크의 `getAttribute('href')`가 `expected(origin, placeA)`와 같다.
   - 확인 1b: `href`를 `new URL()`로 읽은 `pathname.split('/')`이 `['', 'link', 'by', 'traffic', 출발, 도착]` 6칸이다.
   - 확인 1b: 해시와 검색 문자열이 비어 있다.
2. 확인 1c: `근교 유명 맛집` 탭에서 `50% 할인 #1 & 국수?` 행을 펼치면 `href`가 `expected(origin, placeB)`와 같다.
3. 확인 2: `현재 위치로`로 다시 찾고 행을 펼치면 `href`가 `.../traffic/${encodeURIComponent('현재 위치')},37.498,127.027/...`로 시작한다.
4. 확인 3: 링크의 `target`은 `_blank`이고 `rel`은 `noreferrer`다.
5. 확인 4: `/s/s1`을 열고 행을 펼치면 `href`가 `expected({ name: '강남역', lat: 37.498, lng: 127.0276 }, placeA)`와 같다.
6. 확인 5: 375px에서 행을 펼친 채 `document.documentElement.scrollWidth <= 375`. 스크린샷을 `/Users/limdongju/.claude/jobs/457a919a/tmp/h4-ui/row-375.png`에 저장한다.

Vite와 Chrome을 띄운다.

```bash
cd frontend && npx vite --port 5174 --strictPort   # 백그라운드
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --remote-debugging-port=9222 \
  --user-data-dir=/Users/limdongju/.claude/jobs/457a919a/tmp/chrome-h4 about:blank   # 백그라운드
```

- [ ] **Step 2: 드라이버를 돌려 실패를 본다**

Run: `node .superpowers/tools/transit-link.mjs`
Expected:
- 1, 1b, 1c, 2, 3, 4는 FAIL이다(링크가 없다).
- 5는 PASS여도 된다.

- [ ] **Step 3: `PlaceTable.tsx`를 고친다**

`origin` 타입을 넓힌다.

```tsx
type Props = {
  food: string
  nearby: Place[]
  famous: Place[]
  origin: Places['origin']
  course?: CoursePicker
}
```

import는 `import type { Place, Places } from './types.ts'`로 바꾼다.

`sortPlaces` 아래에 링크 함수를 더한다.

```tsx
const TRANSIT_BASE = 'https://map.kakao.com/link/by/traffic/'

// Kakao Map reads `name,lat,lng`, so commas and slashes in a name would split it
function point({ name, lat, lng }: { name: string; lat: number; lng: number }): string {
  return `${encodeURIComponent(name.replace(/[,/]/g, ' '))},${lat},${lng}`
}

// public-transit directions from where the search started to the restaurant
function transitUrl(origin: Places['origin'], place: Place): string {
  return `${TRANSIT_BASE}${point(origin)}/${point(place)}`
}
```

`<PlaceRow …>` 호출에 `transit={transitUrl(origin, place)}`를 더한다. 이어서 `PlaceRowProps`, `PlaceRow`, `Reviews`가 그 값을 넘겨받게 한다.

```tsx
type PlaceRowProps = {
  place: Place
  open: boolean
  onToggle: () => void
  transit: string
  course?: CoursePicker
}

function PlaceRow({ place, open, onToggle, transit, course }: PlaceRowProps) {
  // …
            <Reviews place={place} transit={transit} course={course} />
  // …
}

function Reviews({ place, transit, course }: { place: Place; transit: string; course?: CoursePicker }) {
  // …
        <MapLink href={place.kakaoUrl} label="카카오맵" />
        {place.googleUrl && <MapLink href={place.googleUrl} label="Google 지도" />}
        <MapLink href={transit} label="대중교통 길찾기" />
  // …
}
```

- [ ] **Step 4: 빌드·린트와 드라이버를 돌린다 (GREEN)**

Run: `cd frontend && npm run build && npm run lint`
Expected: 오류와 경고가 없다.

Run: `node .superpowers/tools/transit-link.mjs`
Expected: 확인 1–5가 모두 PASS다.

`row-375.png`를 직접 열어 버튼 줄이 줄바꿈되고 잘리지 않았는지 본다.

- [ ] **Step 5: 정리하고 커밋한다**

Vite와 Chrome을 끈다.

```bash
git add frontend/src/shared/places/PlaceTable.tsx
git commit -m "feat: open transit directions to a restaurant in Kakao Map

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
