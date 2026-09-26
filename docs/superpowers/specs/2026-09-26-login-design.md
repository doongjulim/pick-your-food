# Pick Your Food — C2a. 카카오 로그인 설계

- 작성일: 2026-09-26
- 범위: 서브 프로젝트 C2a (계정, 카카오 로그인·로그아웃, 세션 유지)
- 선행: A·B·C1. 이후 C2b(결과 저장·공유)가 이 계정과 401 규칙을 사용한다.
- 패키지 구조: `CLAUDE.md`의 package-by-feature 규칙. 새 기능 `account`(백엔드 `com.example.pickyourfood.account`, 프론트엔드 `features/account/`).

## 1. 목표

사용자가 카카오 계정으로 로그인해 C2b의 저장 기능을 쓸 수 있는 계정을 갖는다. 로그인은 선택이며, 지금 기능(뽑기·추천·맛집·데이트 코스)은 로그인 없이 그대로 쓴다.

성공 기준:
- `카카오로 로그인` → 카카오 동의(닉네임) → 앱으로 돌아와 오른쪽 위에 `{닉네임}님`이 보인다.
- 서버를 재시작하거나 브라우저를 닫았다 열어도 30일 동안 로그인이 유지된다.
- 로그아웃하면 서버 세션이 즉시 삭제된다.
- 키가 없어도 앱은 뜨고, 기존 테스트는 그대로 통과한다.

## 2. 결정 사항

| 항목 | 결정 |
|------|------|
| 로그인 방식 | 카카오 로그인(OAuth 2.0). 키는 기존 `KAKAO_REST_KEY`, 클라이언트 시크릿을 켰다면 `KAKAO_CLIENT_SECRET` |
| 받는 정보 | 카카오 회원번호, 닉네임(`profile_nickname`). 이메일·사진은 받지 않는다 |
| DB | H2 파일 DB(`./data/pickyourfood`, git 제외), 스키마는 Flyway. 표준 SQL만 사용해 나중에 PostgreSQL로 옮길 수 있게 한다 |
| 세션 | Spring Session JDBC로 DB에 저장, 30일. 쿠키 `HttpOnly`, `SameSite=Lax`, 쿠키 수명 30일 |
| 로그인 필요 범위 | 없음(선택). C2b의 저장·기록 API만 로그인 필요 |

## 3. 백엔드 (`com.example.pickyourfood.account`)

새 의존성: Spring Security, OAuth2 Client, JDBC(`JdbcClient`), Flyway, Spring Session JDBC, H2. JPA는 쓰지 않는다.

| 파일 | 역할 |
|------|------|
| `SecurityConfig` | OAuth2 로그인, 로그아웃, CSRF, 접근 규칙 |
| `AccountService` | 로그인 성공 시 카카오 회원번호로 계정을 찾거나 만들고 닉네임을 최신 값으로 갱신 |
| `AccountRepository` | `JdbcClient`로 `account` 읽기·쓰기 |
| `AccountController` | `GET /api/me` |

### DB (Flyway, `src/main/resources/db/migration/`)

- `V1__account.sql`: `account(id bigint identity primary key, kakao_id varchar(64) not null unique, nickname varchar(100) not null, created_at timestamp not null)`
- `V2__spring_session.sql`: Spring Session JDBC 공식 H2 스키마(`SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`). 자동 초기화는 끈다(`spring.session.jdbc.initialize-schema=never`).
- 테스트는 인메모리 H2에 같은 마이그레이션을 적용한다.

### 로그인 흐름

1. 프론트엔드가 페이지 전체를 `/oauth2/authorization/kakao`로 이동한다.
2. 카카오 동의 화면(`scope=profile_nickname`).
3. 카카오가 `/login/oauth2/code/kakao`로 돌려보내면 사용자 정보(`https://kapi.kakao.com/v2/user/me`)의 `id`와 `properties.nickname`(또는 `kakao_account.profile.nickname`)으로 계정을 저장하고 `/`로 이동한다.
4. 실패(동의 취소 등) → `/?login=failed`.

카카오 제공자 설정: 인가 `https://kauth.kakao.com/oauth/authorize`, 토큰 `https://kauth.kakao.com/oauth/token`, 사용자 정보 `https://kapi.kakao.com/v2/user/me`, 사용자 이름 속성 `id`, 클라이언트 인증 `client_secret_post`, Redirect URI `{baseUrl}/login/oauth2/code/kakao`.

키가 없을 때: 등록은 자리표시 클라이언트 id로 만들어 앱이 뜨게 하고, 로그인 시도만 카카오 오류 화면으로 끝난다.

### API

| 요청 | 응답 |
|------|------|
| `GET /api/me` | 로그인: `200 {"id": 1, "nickname": "서윤"}` / 아니면 `401` |
| `POST /api/logout` | `204`, 서버 세션 삭제. CSRF 토큰 필요 |

### 보안 규칙

- 기존 API(`/api/foods/**`, `/api/recommendations`, `/api/places`)와 정적 경로는 누구나.
- 그 밖의 `/api/**`에서 로그인이 필요한데 안 된 요청은 로그인 페이지로 보내지 않고 `401`.
- CSRF: Spring Security SPA 방식(`XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더). GET은 검사하지 않는다.

### 개발 설정

- Vite 프록시에 `/oauth2`, `/login/oauth2` 추가. 프록시가 Host(`localhost:5173`)를 유지하므로 Redirect URI는 5173 주소다.
- 카카오 앱 설정(사용자가 직접): 카카오 로그인 활성화, Redirect URI `http://localhost:5173/login/oauth2/code/kakao`, 동의 항목 닉네임.

## 4. 프론트엔드

`features/account/`:
- `api.ts`: `fetchMe(): Promise<Me | null>`(401 → `null`), `logout()`.
- `AccountMenu.tsx`:
  - 확인 중: 버튼 크기 자리표시(레이아웃 흔들림 없음).
  - 로그인 전: `카카오로 로그인` 링크(`SignIn` 아이콘, 페이지 이동).
  - 로그인 후: `{닉네임}님` 버튼 → `로그아웃` 펼침. 로그아웃하면 바로 로그인 전 상태.
  - `?login=failed`면 "로그인하지 못했어요. 다시 시도해 주세요."를 보이고 주소에서 지운다(`history.replaceState`).

수정:
- `App.tsx`: 모든 화면의 위쪽 오른편에 `AccountMenu`. 기능끼리는 import하지 않는다.
- `shared/api.ts`: GET이 아닌 요청에 `X-XSRF-TOKEN` 헤더(쿠키 값).
- `vite.config.ts`: 프록시 경로 추가.
- `CLAUDE.md`: 기능 목록에 `account`.

디자인: A·B·C1과 동일(zinc + 강조색 1개, Phosphor, 스프링 모션, 이모지 금지). 카카오 노란색은 쓰지 않는다.

한계: 로그인은 카카오를 거쳐 페이지를 다시 열기 때문에 보던 결과 화면은 첫 화면으로 돌아간다. 저장하려던 결과를 이어서 저장하는 처리는 C2b에서 다룬다.

## 5. 테스트

백엔드(`./gradlew test`, 실제 키 불필요):
- `AccountServiceTest`: 새 카카오 회원 → 계정 생성, 같은 회원 재로그인 → 같은 계정 + 닉네임 갱신.
- `AccountRepositoryTest`: 인메모리 H2 + Flyway로 저장·조회.
- `AccountControllerTest`(MockMvc):
  - 비로그인 `/api/me` → 401, `oauth2Login()` → `{id, nickname}`.
  - 로그아웃: CSRF 없음 → 403, 있음 → 204, 이후 `/api/me` → 401.
  - 기존 API는 로그인 없이 200.
  - `/oauth2/authorization/kakao` → `kauth.kakao.com` 리다이렉트, `scope=profile_nickname`.
- 기존 테스트 50개 통과.

프론트엔드: `npm run build` + headless Chrome(가로챈 `/api/*`): 로그인 전 버튼, 로그인 후 닉네임, 로그아웃 후 버튼 복귀, 로그아웃 요청의 `X-XSRF-TOKEN` 헤더, `?login=failed` 안내와 주소 정리, 375px 가로 스크롤 없음.

실제 카카오 로그인은 키·Redirect URI 설정 후 사용자가 직접 확인한다.

## 6. 범위 밖

- 회원 탈퇴, 여러 로그인 방식 연결, Google 로그인.
- 결과 저장·기록·공유(C2b).
- 운영 환경 PostgreSQL, HTTPS(`Secure` 쿠키), 배포 도메인 Redirect URI.
