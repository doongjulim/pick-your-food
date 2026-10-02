# Pick Your Food — F. 회원 탈퇴 설계

- 작성일: 2026-10-02
- 범위: 서브 프로젝트 F (계정·저장 결과 삭제, 카카오 연결 끊기, 확인 화면)
- 선행: C2a(카카오 로그인), C2b(저장·공유), D(배포).
- 패키지 구조: `CLAUDE.md`의 package-by-feature 규칙. 백엔드·프론트엔드 모두 `account` 기능 안에서 끝낸다. `account`는 `saved` 코드를 import하지 않고, 저장 결과는 SQL로 지운다. 두 기능이 함께 쓰게 되는 `HttpTimeouts`는 `place`에서 `food`로 옮긴다.

## 1. 목표

로그인한 사용자가 확인 창 한 번으로 탈퇴하면 계정·저장 결과·공유 링크·세션이 모두 사라지고 카카오 계정 연결도 끊어진다.

성공 기준:
- `DELETE /api/me` 뒤 그 계정의 `account`·`saved_result` 행이 없고, 다른 계정의 행은 그대로다.
- 그 계정의 모든 세션(다른 기기 포함)이 끝나 `GET /api/me`가 401이다.
- 카카오 연결 끊기가 실패하면 아무것도 지우지 않고 502, 사용자는 다시 시도할 수 있다.
- 카카오 계정 설정의 "연결된 서비스"에서 앱이 사라진다(배포 후 사용자 확인).

## 2. 결정 사항

| 항목 | 결정 |
|------|------|
| 카카오 연결 끊기 | 앱 Admin 키(`KAKAO_ADMIN_KEY`) + 저장된 `kakao_id`. 사용자 토큰은 서버 메모리에만 있어 Render가 잠들면 사라진다 |
| 순서 | 카카오 연결 끊기 → 성공하면 우리 데이터 삭제. 실패하면 아무것도 지우지 않는다 |
| 이미 끊긴 사용자 | 카카오 오류 코드 `-101`은 성공으로 본다(사용자가 카카오에서 먼저 끊었거나, 이전 시도가 DB 단계에서 실패한 경우) |
| 저장 결과 삭제 | `AccountRepository`가 SQL로 `saved_result` → `account` 순서, 한 트랜잭션. FK `ON DELETE CASCADE`는 제약 이름이 DB마다 자동 생성이라 쓰지 않는다 |
| 키 없음(로컬) | 카카오 호출을 건너뛰고 우리 데이터만 지운다 |
| 확인 화면 | 브라우저 기본 `<dialog>` + `showModal()`. 새 의존성 없음 |

## 3. 백엔드 (`com.example.pickyourfood.account`)

### API: `DELETE /api/me` → 204

- 로그인 필요(없으면 기존 규칙대로 401). CSRF 토큰 필요(기존 `csrf.spa()`, 없으면 403).
- 처리 순서:
  1. `AccountRepository.kakaoId(accountId)`. 없으면(이미 탈퇴) 4단계로 건너뛴다.
  2. `KakaoUnlink.unlink(kakaoId)`. 예외 → 502(`ResponseStatusException(BAD_GATEWAY)`), 아무것도 지우지 않고 세션도 유지.
  3. `AccountRepository.delete(accountId)`: `delete from saved_result where account_id = ?` → `delete from account where id = ?`, 한 트랜잭션.
  4. 그 계정의 모든 세션 삭제: `FindByIndexNameSessionRepository.findByPrincipalName(String.valueOf(accountId))`로 찾아 `deleteById`. 현재 요청의 세션은 `request.getSession().invalidate()`로 끝내고 보안 컨텍스트도 비운다.

### `KakaoUnlink` (새 파일)

- `POST https://kapi.kakao.com/v1/user/unlink`
  - 헤더 `Authorization: KakaoAK {admin key}`
  - form 본문 `target_id_type=user_id`, `target_id={kakaoId}`
- 응답 400이고 본문 `code`가 `-101`이면 정상 종료. 그 밖의 HTTP 오류·네트워크 오류는 `RestClientException`으로 던진다.
- 키가 비어 있으면 요청을 보내지 않는다.
- `RestClient`와 `food.HttpTimeouts.factory()`(연결·읽기 타임아웃). 테스트용 생성자 `KakaoUnlink(RestClient.Builder, String key)`는 `place.KakaoClient`와 같은 모양.

### `AccountRepository`

- `Optional<String> kakaoId(long id)`
- `void delete(long id)` (위 3단계)

### `HttpTimeouts` 이동

`place/HttpTimeouts.java` → `food/HttpTimeouts.java`(`public`). `place`의 사용처는 import만 바뀐다.

### 설정·배포·문서

- `application.properties`: `kakao.admin-key=${KAKAO_ADMIN_KEY:}`
- `render.yaml`: `KAKAO_ADMIN_KEY` (`sync: false`)
- `README.md` 배포 절차: 카카오 개발자 콘솔 → 앱 키 → Admin 키를 Render에 `KAKAO_ADMIN_KEY`로 넣는다. 이 키는 앱 전체 권한이라 서버 환경변수에만 둔다.

## 4. 화면 (`frontend/src/features/account/`)

### 메뉴 (`AccountMenu.tsx`)

- 드롭다운 "로그아웃" 아래에 "회원 탈퇴"(Phosphor `UserMinus`, 다른 항목보다 옅은 회색). 누르면 메뉴를 닫고 확인 창을 연다.
- 탈퇴 성공 → 로그아웃 상태("카카오로 로그인"), 그 아래 `탈퇴했어요. 그동안 고마웠어요.`(로그인 실패 안내와 같은 자리·스타일, `role="status"`).

### 확인 창 (`DeleteAccountDialog.tsx`, 새 파일)

- `<dialog>`를 `showModal()`로 연다(포커스 가두기, Esc 닫기).
- 제목 `정말 탈퇴할까요?`, 본문 `저장한 결과와 공유 링크가 모두 사라지고 되돌릴 수 없어요. 카카오 계정 연결도 끊어져요.`
- 버튼 `취소`(테두리) / `탈퇴하기`(강조색).
- 진행 중: 두 버튼 비활성, 강조 버튼 `탈퇴하는 중…`, Esc로 닫히지 않는다(`cancel` 이벤트 막기).
- 실패: 창 안에 `탈퇴하지 못했어요. 다시 시도해 주세요.`(`role="alert"`), 창은 열린 채로 다시 시도 가능.

### API (`api.ts`)

`deleteAccount(): Promise<void>` → `request('/api/me', { method: 'DELETE' })`. CSRF 헤더는 기존 `shared/api.ts`의 `request`가 붙인다.

### 다른 화면

App·다른 기능은 바꾸지 않는다. 저장한 결과 화면은 다음에 열 때 401을 받아 로그인 안내를 보여준다(로그아웃과 같다).

## 5. 테스트

백엔드(`./gradlew test`, Docker 불필요):
- `KakaoUnlinkTest`(`MockRestServiceServer`):
  - 요청: `POST /v1/user/unlink`, `Authorization: KakaoAK test-admin`, 본문 `target_id_type=user_id&target_id=12345`.
  - 400 + `{"msg":"NotRegisteredUserException","code":-101}` → 예외 없음.
  - 400 + 다른 코드, 500 → `RestClientException`.
  - 빈 키 → 요청 없음.
- `AccountDeletionTest`(`@SpringBootTest` + MockMvc, `KakaoUnlink`는 `@MockitoBean`):
  - 로그인 세션으로 `DELETE /api/me` → 204. 내 `account`·`saved_result` 행 없음, 다른 계정의 저장 결과는 남음, `unlink`가 내 `kakao_id`로 호출, 같은 세션 `GET /api/me` 401, 같은 계정의 두 번째 세션도 401.
  - `unlink` 예외 → 502, 계정·저장 결과·세션 유지(`GET /api/me` 200).
  - CSRF 토큰 없음 → 403, 아무것도 안 지워짐.
  - 로그인 없음 → 401.
  - 이미 지워진 계정의 세션 → 204, `unlink` 호출 없음.
- `PostgresMigrationTest`: 기존 흐름 끝에 `DELETE /api/me`(204)와 계정 행 삭제 확인. `KakaoUnlink`는 키가 없어 호출되지 않는다.
- 기존 86개 모두 통과.

화면(로컬): `bootRun` + `npm run dev`, 테스트 세션으로 로그인 상태 → 메뉴 → 회원 탈퇴 → 확인 창 → 탈퇴하기 → 로그아웃 상태와 안내 문구. 서버를 끈 상태로 실패 문구. 헤드리스 Chrome 스크린샷.

배포 후(사용자): Render에 `KAKAO_ADMIN_KEY` 입력 → 실제 계정으로 탈퇴 → 카카오톡 설정 → 카카오계정 → 연결된 서비스에서 앱이 사라졌는지 확인.

## 6. 범위 밖

- 탈퇴 사유 입력, 유예 기간(나중 삭제), 탈퇴 기록 보관.
- 탈퇴 전 닉네임 입력 확인, 저장 결과 개수 표시.
- 카카오 쪽에서 연결을 끊었을 때 받는 연결 끊기 알림(webhook) 처리.
