# Pick Your Food — H3. 저장 결과 이름 바꾸기·공유 끄기 설계

- 작성일: 2026-10-09
- 범위: 서브 프로젝트 H3 (저장한 결과의 이름을 바꾸고, 공유 링크를 끄고 켤 수 있다)
- 선행: C2b(저장·공유), G1(저장 목록).
- 패키지 구조: `CLAUDE.md`의 package-by-feature 규칙. 백엔드는 `saved`(컨트롤러, 저장소, 공유 페이지)와 마이그레이션 V4, 프론트엔드는 `features/saved/`(`api.ts`, `SavedView.tsx`, `SavedList.tsx`)만 바뀐다. `SecurityConfig`는 바꾸지 않는다(`/api/**`의 PATCH는 이미 로그인과 CSRF 토큰이 필요하다).

## 1. 목표

지금 저장 결과의 id가 곧 공유 링크라서, 링크를 가진 사람은 언제나 결과를 볼 수 있고 이름도 저장할 때 정해진 그대로다. 주인이 결과의 이름을 바꾸고, 공유 링크를 끄고 다시 켤 수 있게 한다.

성공 기준:
- 주인은 저장 결과 화면에서 이름을 바꿀 수 있고, 새 이름이 결과 화면·저장 목록·공유 미리보기 카드에 나온다.
- 주인이 공유를 끄면 다른 사람(로그인 안 한 사람, 다른 계정)은 그 링크에서 삭제된 결과와 똑같이 `이 결과를 찾을 수 없어요`를 보고, 미리보기 카드는 기본 카드다.
- 공유를 꺼도 주인은 결과를 그대로 본다. 다시 켜면 같은 링크가 다시 열린다.
- 저장 목록에서 공유를 끈 결과에 `비공개` 표시가 붙는다.

## 2. 결정 사항

| 항목 | 결정 |
|------|------|
| 공유를 끈 결과를 남이 열 때 | 삭제된 결과와 똑같이 404. 결과가 있다는 사실도 드러내지 않는다 |
| 새 결과·기존 결과의 처음 상태 | 공유 켜짐(지금 동작 그대로, 이미 보낸 링크가 막히지 않게) |
| 바꾸는 곳 | 저장 결과 화면에서 주인에게만. 목록은 `비공개` 표시만 |
| 이름의 기준 | `saved_result.title` 컬럼. payload는 저장할 때 그대로 두고, 읽을 때 `result.title`을 컬럼 값으로 바꿔 보낸다 |
| API | `PATCH /api/saved/{id}` 하나, 본문 `{ title?, shared? }` |
| 화면 반영 | 서버가 성공을 돌려준 뒤에만 바꾼다(미리 바꾸지 않는다) |

## 3. 백엔드 (`saved`)

### 마이그레이션 `db/migration/V4__saved_result_shared.sql`

```sql
alter table saved_result add column shared boolean not null default true;
```

H2와 PostgreSQL 모두 같은 문법이라 `db/migration/`에 둔다.

### `SavedRepository`

- `Stored(long accountId, String title, boolean shared, String payload, Instant createdAt)`: `find`가 `title`, `shared`도 읽는다.
- `Summary(id, title, foodName, originName, createdAt, shared)`: `findByAccount`가 `shared`도 읽는다.
- `boolean update(String id, long accountId, String title, Boolean shared)`: null이 아닌 값만 고친다. `where id = ? and account_id = ?`로 고친 줄이 1이면 true(없거나 남의 결과면 false). null 파라미터의 타입 추론 문제를 피하려고 고칠 필드에 따라 SQL을 만든다(예: `title`만, `shared`만, 둘 다).

### `SavedController`

- `GET /api/saved/{id}`:
  - `mine`을 계산한 뒤 `!stored.shared() && !mine`이면 404.
  - 응답 `View(id, createdAt, mine, shared, result)`. `result`는 payload를 읽은 `SavedResult`에서 `title`만 컬럼 값으로 바꾼 것.
- `PATCH /api/saved/{id}` (204):
  - 본문 `Change(String title, Boolean shared)`.
  - `title`이 있으면 `strip()`한 값이 비어 있지 않고 100자 이하. 두 필드가 모두 null이면 400.
  - `saved.update(...)`가 false면 404(삭제와 같은 이유: 남의 결과 id를 떠볼 수 없게).
- 로그인하지 않은 PATCH는 기존 보안 설정대로 401(CSRF 토큰이 없으면 403).

### `SharePageController`

- `saved.find(id)`의 결과가 `shared`가 아니면 기본 카드.
- 카드 제목의 이름은 컬럼의 `title`(`음식 이름 · 이름`). 설명은 지금처럼 payload에서.

## 4. 화면 (`features/saved/`)

### `api.ts`

- `SavedEntry.shared: boolean`, `SavedSummary.shared: boolean`.
- `updateSaved(id: string, change: { title?: string; shared?: boolean }): Promise<void>`: `PATCH /api/saved/{id}`(공용 `request`가 CSRF 토큰을 붙인다).

### `SavedView.tsx` (주인 `entry.mine`일 때만 새 컨트롤)

- 이름(`entry.result.title`, 지금 `text-sm text-zinc-500` 줄) 옆에 `이름 바꾸기` 버튼(Phosphor `PencilSimple`, `aria-label="이름 바꾸기"`).
  - 누르면 그 줄이 폼으로 바뀐다: 라벨 `이름`(입력 위), `<input maxLength={100} required>`(지금 이름으로 채우고 포커스), `저장`·`취소`.
  - 앞뒤 공백을 지우고 비어 있으면 보내지 않는다(`required`와 함께 `저장` 비활성).
  - 보내는 중에는 `저장` 비활성. 성공하면 폼을 닫고 새 이름을 보여 준다. 실패하면 폼은 그대로, 입력 아래 `이름을 바꾸지 못했어요. 다시 시도해 주세요.`(`role="alert"`).
  - `취소`나 Escape는 바꾸지 않고 닫는다. 닫히면 포커스는 `이름 바꾸기` 버튼으로.
- 버튼 줄의 `링크 공유` 앞에 공유 스위치: `<button role="switch" aria-checked={shared}>` 라벨 `공유`.
  - 누르면 `updateSaved(id, { shared: !shared })`. 보내는 중에는 비활성, 성공하면 상태를 바꾼다. 실패하면 `공유 설정을 바꾸지 못했어요. 다시 시도해 주세요.`(`role="alert"`).
  - 꺼져 있으면 `링크 공유` 버튼을 숨기고 `공유가 꺼져 있어요. 다른 사람은 이 링크를 열 수 없어요.`를 보여 준다.
- 주인이 아니면 지금과 같다(스위치·이름 바꾸기 없음, `링크 공유`는 보인다).

### `SavedList.tsx`

- `shared`가 false인 줄은 보조 문구 앞에 `비공개` 표시(Phosphor `LockSimple` + 글자, `text-zinc-500`).

## 5. 테스트

백엔드(`./gradlew test`):
- `SavedControllerTest`:
  - 이름 바꾸기 → 목록의 `title`과 상세의 `result.title`이 새 이름(앞뒤 공백 제거).
  - 공유 끄기 → 상세에 `shared: false`, 목록에 `shared: false`. 로그인 안 한 사람과 다른 계정은 404, 주인은 200.
  - 다시 켜기 → 로그인 안 한 사람이 다시 200.
  - 로그인 안 한 PATCH는 거절(401/403), 다른 계정의 PATCH와 없는 id는 404이고 결과는 바뀌지 않는다.
  - 빈 이름(`"  "`), 101자 이름, 빈 본문 `{}` → 400.
- `SharePageControllerTest`: 공유를 끈 결과는 기본 카드, 이름을 바꾼 결과는 카드 제목에 새 이름.
- `PostgresMigrationTest`(Docker가 있을 때): V4까지 PostgreSQL에서 돈다.

화면: `npm run build && npm run lint` + 헤드리스 Chrome(가로챈 `/api/*`). 확인 항목:
1. 주인: `이름 바꾸기` → 입력 → `저장` → 새 이름이 보이고 요청 본문이 `{ "title": … }`.
2. 이름 바꾸기 500 → 폼이 남고 알림.
3. 공유 스위치 끄기 → `aria-checked="false"`, `링크 공유`가 사라지고 안내 문구. 다시 켜면 돌아온다.
4. 공유 스위치 500 → 상태 그대로, 알림.
5. 주인이 아니면 스위치·`이름 바꾸기`가 없다.
6. 저장 목록에서 공유를 끈 결과에 `비공개`.
7. 375px에서 가로로 넘치지 않는다.

## 6. 범위 밖

- 링크 새로 만들기(id 바꾸기), 공유 기간 정하기.
- 목록에서 한꺼번에 이름 바꾸기·공유 바꾸기.
- 메뉴·장소 등 결과 내용 고치기.
