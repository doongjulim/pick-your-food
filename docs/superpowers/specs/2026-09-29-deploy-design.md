# Pick Your Food — D. 배포 설계

- 작성일: 2026-09-29
- 범위: 서브 프로젝트 D (Render 배포, Neon PostgreSQL, 운영 설정, 바로 들어오는 주소 처리)
- 선행: A·B·C1·C2a·C2b. C2b의 공유 링크(`/s/{id}`)를 다른 사람이 실제로 열 수 있게 한다.
- 패키지 구조: `CLAUDE.md`의 package-by-feature 규칙. 새 기능 `web`(백엔드 `com.example.pickyourfood.web`, 앱 전체의 화면 경로 처리). 프론트엔드 변경 없음.

## 1. 목표

앱을 인터넷에 올려 누구나 주소로 열고, 공유 링크를 받은 사람도 바로 결과를 본다.

성공 기준:
- `https://<앱 이름>.onrender.com`에서 뽑기·추천·맛집·데이트 코스가 로컬과 똑같이 동작한다.
- 카카오 로그인이 배포 주소로 돌아오고, 저장한 결과는 서버 재시작·재배포 뒤에도 남는다.
- `/s/{id}`·`/saved` 주소를 새 창에 바로 열어도 화면이 뜬다.
- 로컬 개발(`./gradlew bootRun` + `npm run dev`, H2)과 `./gradlew test`는 지금과 똑같이 Docker 없이 돈다.

## 2. 결정 사항

| 항목 | 결정 |
|------|------|
| 플랫폼 | Render 웹 서비스(무료, Docker, 싱가포르). `render.yaml` Blueprint, `main` 푸시 시 자동 배포 |
| DB | Neon PostgreSQL 무료(만료 없음, AWS `ap-southeast-1`). 로컬·테스트는 H2 유지 |
| 프론트엔드 포함 | 여러 단계 Dockerfile: Node로 빌드 → `static/`에 넣어 jar → JRE 이미지. Gradle 빌드는 Node를 모른다 |
| 운영 설정 | `prod` 프로필: 포트 `PORT`, 프록시 헤더 신뢰, 세션 쿠키 `Secure` |
| 비밀 값 | Render 대시보드에서 입력(`sync: false`). 저장소에는 없다 |

## 3. 백엔드

### DB

- 의존성: `org.postgresql:postgresql`(runtime), `org.flywaydb:flyway-database-postgresql`(Flyway 10부터 PostgreSQL 지원이 별도 모듈).
- `spring.flyway.locations=classpath:db/migration,classpath:db/vendor/{vendor}`.
  - V1(`account`)·V3(`saved_result`)는 표준 SQL이라 `db/migration`에 그대로.
  - V2(Spring Session)는 H2 전용 타입(`LONGVARBINARY`)을 쓰므로 `db/vendor/h2/V2__spring_session.sql`로 옮긴다(내용 그대로 → 체크섬 같음 → 기존 로컬 `./data` DB가 그대로 이어진다).
  - `db/vendor/postgresql/V2__spring_session.sql`: Spring Session JDBC 공식 PostgreSQL 스키마(`ATTRIBUTE_BYTES BYTEA`).
- 접속 정보는 환경변수 `SPRING_DATASOURCE_URL`(`jdbc:postgresql://<host>/<db>?sslmode=require`), `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`. 설정 파일은 바꾸지 않는다(환경변수가 `application.properties`의 H2 주소를 덮는다).

### `prod` 프로필 (`application-prod.properties`)

- `server.port=${PORT:8080}`: Render가 주는 포트.
- `server.forward-headers-strategy=framework`: Render가 HTTPS를 받아 http로 넘기므로 `X-Forwarded-*`로 원래 주소를 복원한다. 카카오 Redirect URI(`{baseUrl}/login/oauth2/code/kakao`)가 `https://<앱>.onrender.com/…`이 되고, CSRF 쿠키도 요청을 HTTPS로 본다.
- `server.servlet.session.cookie.secure=true`.
- 로컬 개발에는 켜지 않는다(Safari는 http의 `Secure` 쿠키를 받지 않는다).

### 바로 들어오는 주소 (`web`)

- `SpaController`: `GET /s/{id}`, `GET /saved` → `forward:/index.html`. 보안 규칙은 이미 `anyRequest().permitAll()`이라 변경 없음.
- 어느 기능에도 속하지 않는 앱 전체 경로라 새 기능 `web`에 둔다. 다른 기능을 import하지 않는다.

### 카카오 (사용자가 직접)

카카오 개발자 콘솔 Redirect URI에 `https://<앱 이름>.onrender.com/login/oauth2/code/kakao` 추가(로컬 `http://localhost:5173/…`는 유지).

## 4. 빌드와 배포

### `Dockerfile` (저장소 루트)

1. `node:22-alpine`: `frontend/`에서 `npm ci` → `npm run build` → `frontend/dist`.
2. `eclipse-temurin:17-jdk`: Gradle wrapper·`build.gradle`·`settings.gradle`·`src` 복사, 1단계 `dist`를 `src/main/resources/static/`에 넣고 `./gradlew bootJar -x test`(테스트는 로컬에서 푸시 전에 돈다).
3. `eclipse-temurin:17-jre`: jar만 복사, root가 아닌 사용자, `java -XX:MaxRAMPercentage=75 -jar app.jar`(무료 요금제 512MB).

### `.dockerignore`

`node_modules`, `frontend/dist`, `build`, `.gradle`, `data`, `graphify-out`, `.claude`, `.serena`, `.superpowers`, `.git`.

### `render.yaml`

- 웹 서비스 `pick-your-food`: `runtime: docker`, `plan: free`, `region: singapore`, `autoDeploy: true`(`main`), `healthCheckPath: /api/foods/random`(로그인·DB 없이 200).
- 환경변수: `SPRING_PROFILES_ACTIVE=prod`. `sync: false`: `KAKAO_REST_KEY`, `KAKAO_CLIENT_SECRET`, `GOOGLE_PLACES_KEY`, `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.

### 문서

- `README.md`: 배포 절차(Neon DB 만들기·접속 정보 → Render Blueprint 연결 → 비밀 값 입력 → 카카오 Redirect URI 추가).
- `CLAUDE.md`: Commands에 로컬 운영 모드(`docker build`·`docker run`), Layout 기능 목록에 `web`.

한계: 무료 요금제는 15분 동안 요청이 없으면 잠든다. 깨어날 때 Spring Boot 시작과 Neon 재개로 첫 요청이 30초~1분 걸릴 수 있다.

## 5. 테스트

백엔드(`./gradlew test`, Docker 불필요):
- `SpaControllerTest`: `GET /s/abc`·`/saved` → `index.html`로 forward. `/api/saved/abc`는 API가 그대로 처리.
- `ProdProfileTest`(`prod` 프로필, H2): `X-Forwarded-Proto: https`, `X-Forwarded-Host: pick.example`로 `/oauth2/authorization/kakao` → Redirect URI `https://pick.example/login/oauth2/code/kakao`, 세션 쿠키 `Secure`.
- 기존 71개 통과(V2 이동 후에도 H2 마이그레이션 유지).

PostgreSQL(Testcontainers, 테스트 전용 의존성):
- `PostgresMigrationTest`: 실제 PostgreSQL에서 V1~V3 적용, 세션 저장·조회(`BYTEA`), 계정 생성, 결과 저장·조회·삭제.
- `disabledWithoutDocker = true`: Docker가 꺼져 있으면 건너뛴다. 구현 중에는 Docker Desktop을 켜고 실제로 도는지 확인한다.

이미지(로컬, Docker): `docker build` → PostgreSQL 컨테이너 + 앱 컨테이너(`prod`) → `/`, `/s/xyz`, `/saved`가 200 `index.html`, `/api/foods/random` 200, 헤드리스 Chrome 첫 화면 스크린샷, 컨테이너 메모리 512MB 이내.

실제 배포는 사용자가 한다(Neon, Render, 카카오 설정). 배포 주소를 받으면 첫 화면과 `/api/foods/random`을 GET으로 확인하고, 카카오 로그인·저장·공유는 사용자가 직접 확인한다.

## 6. 범위 밖

- 직접 구입한 도메인 연결.
- 여러 인스턴스·무중단 배포, 모니터링·로그 수집.
- 공유 링크 미리보기(Open Graph 카드).
- GitHub Actions CI(Render 자동 배포로 충분).
