# Pick Your Food — E. 공유 링크 미리보기 설계

- 작성일: 2026-10-01
- 범위: 서브 프로젝트 E (공유 링크 `/s/{id}`의 Open Graph 카드)
- 선행: C2b(저장·공유 링크), D(배포). 배포된 공유 링크를 카카오톡·메신저에 붙였을 때 내용이 보이게 한다.
- 패키지 구조: `CLAUDE.md`의 package-by-feature 규칙. 저장 결과를 읽어야 하므로 `/s/{id}` 처리는 `saved`로 옮긴다(`web`은 다른 기능을 import하지 않는다). 프론트엔드 코드 변경 없음.

## 1. 목표

공유 링크를 메신저에 붙이면 그 결과의 메뉴·제목·설명과 큰 이미지가 카드로 보인다. 링크를 연 사람은 지금과 똑같이 앱 화면을 본다.

성공 기준:
- `/s/{id}` 응답 HTML의 `<head>`에 그 결과의 Open Graph 메타 태그가 있고, `<title>`도 같은 제목이다.
- 없는 링크·읽을 수 없는 결과도 200과 앱 화면, 기본 카드를 돌려준다.
- 사용자가 저장한 문자열은 이스케이프되어 HTML을 깨거나 스크립트를 넣지 못한다.
- 운영(프록시 뒤)에서 `og:url`·`og:image`가 `https://<앱>.onrender.com/…` 절대 주소다.

## 2. 결정 사항

| 항목 | 결정 |
|------|------|
| 넣는 방식 | 빌드된 `static/index.html`을 읽어 `<head>` 바로 뒤에 메타 태그를 문자열로 끼워 넣는다. 템플릿 엔진·새 의존성 없음 |
| 대상 | 사람·봇 구분 없이 같은 응답(User-Agent 판별 없음) |
| 이미지 | 고정 이미지 한 장 `og.png`(1200×630) |
| 범위 | `/s/{id}`만. `/`·`/saved`는 지금 그대로 |

## 3. 카드 내용

| 메타 | 결과가 있을 때 | 없는 링크·읽기 실패 |
|------|----------------|---------------------|
| `og:title`, `<title>` | `{대표 메뉴 이름} · {제목}` (예: `탄탄멘 · 오늘의 랜덤 메뉴`) | `오늘 뭐 먹지` |
| `og:description` | 대표 메뉴 설명. 맛집 포함(`places` 있음)이면 ` · {출발지} 근처 맛집과 데이트 코스`를 붙인다. 150자를 넘으면 150자로 자르고 `…` | `오늘 먹을 메뉴를 골라 드려요` |
| `og:image` | `{현재 요청의 scheme://host[:port]}/og.png` | 같음 |
| `og:url` | 현재 요청 주소(`…/s/{id}`) | 같음 |
| 공통 | `og:type=website`, `og:site_name=오늘 뭐 먹지`, `twitter:card=summary_large_image` | 같음 |

- 대표 메뉴 설명이 비어 있으면(`null`·공백) 설명은 출발지 문구만, 그것도 없으면 기본 설명을 쓴다.
- 주소는 현재 요청에서 만든다(`ServletUriComponentsBuilder.fromCurrentRequest`). 운영은 D의 `server.forward-headers-strategy=framework`로 `X-Forwarded-Proto`가 반영된다.

## 4. 백엔드

### `saved/SharePageController` (새 파일)

- `GET /s/{id}` → `text/html; charset=UTF-8`, 200.
- `SavedRepository.find(id)`로 결과를 읽고, payload를 `SavedResult`로 읽는다. 결과가 없거나 payload를 읽다 예외가 나면 기본 카드.
- `index.html`은 classpath `static/index.html`을 처음 요청 때 읽어 필드에 둔다(빌드 결과는 실행 중에 바뀌지 않는다).
- 끼워 넣기: 첫 `<head>` 바로 뒤에 메타 태그들, 기존 `<title>…</title>`을 카드 제목으로 바꾼다.
- 모든 값(제목·설명·주소)은 `org.springframework.web.util.HtmlUtils.htmlEscape`로 이스케이프해 `content="…"`에 넣는다.
- 보안 규칙은 이미 `anyRequest().permitAll()`이라 변경 없음. 응답은 로그인 여부와 무관하다.

### `web/SpaController`

`@GetMapping`에서 `/s/{id}`를 빼고 `/saved`만 남긴다.

## 5. 이미지

- `frontend/public/og.png`(1200×630 PNG). Vite가 `dist/`로 그대로 복사하므로 운영에서 `/og.png`로 열린다.
- 만드는 법: 앱 색감·Pretendard로 "오늘 뭐 먹지" 카드를 HTML로 그려 헤드리스 Chrome으로 한 번 캡처해 커밋한다. 원본 HTML은 커밋하지 않는다.

## 6. 문서

- `README.md` 배포 절차 뒤에: 카카오는 카드를 캐시하므로 바뀐 카드를 바로 보려면 카카오 개발자 도구의 공유 디버거에서 캐시를 지운다.

## 7. 테스트

백엔드(`./gradlew test`, Docker 불필요):
- 테스트 전용 `src/test/resources/static/index.html`(작은 가짜 `index.html`: `<head>`, `<title>오늘 뭐 먹지</title>`, `<div id="root"></div>`). 실제 프론트엔드 빌드는 테스트 classpath에 없다.
- `SharePageControllerTest`:
  - 저장된 결과 → `og:title`(`탄탄멘 · 오늘의 랜덤 메뉴`)·`og:description`·`og:image`(`/og.png`로 끝남)·`og:url`(`/s/{id}`로 끝남), `<title>` 교체, `<div id="root">` 유지.
  - 맛집 포함 결과 → 설명에 `{출발지} 근처 맛집과 데이트 코스`.
  - 제목에 `"><script>alert(1)</script>` → 응답에 `<script>alert(1)`이 없고 `&lt;script&gt;`가 있다.
  - 150자 넘는 설명 → 150자 + `…`.
  - 없는 id → 200, `og:title` `오늘 뭐 먹지`, 기본 설명.
- `ProdProfileTest`에 추가: `prod` 프로필, `X-Forwarded-Proto: https`로 `/s/{없는 id}` → `og:url`·`og:image`가 `https://`로 시작.
- `SpaControllerTest`: `/s/abc` forward 검사를 지우고 `/saved`만 남긴다.
- 기존 테스트 모두 통과.

이미지(로컬, Docker): `docker build` → PostgreSQL + 앱(`prod`) 컨테이너 → 저장 결과를 하나 넣고 `/s/{id}`가 그 결과의 `og:title`을 담은 실제 빌드 `index.html`(스크립트 태그 포함), `/og.png` 200 `image/png`.

실제 카카오톡 카드는 배포 후 사용자가 확인한다.

## 8. 범위 밖

- 메인 주소(`/`)·`/saved`의 카드.
- 결과마다 다른 이미지(서버 이미지 생성).
- 카카오 캐시 갱신 자동화.
- 로컬 개발 서버(`npm run dev`)에서의 미리보기(`/s/{id}`를 Vite가 처리).
