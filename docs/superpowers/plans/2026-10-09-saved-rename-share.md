# H3 저장 결과 이름 바꾸기·공유 끄기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 주인이 저장 결과의 이름을 바꾸고 공유 링크를 끄고 켤 수 있게 한다.

**Architecture:** `saved_result`에 `shared` 컬럼(V4)을 더하고, 이름의 기준을 `title` 컬럼으로 삼는다. `PATCH /api/saved/{id}`가 둘을 고치고, `GET /api/saved/{id}`와 `/s/{id}`는 공유가 꺼진 결과를 주인이 아닌 사람에게 삭제된 결과처럼 보여 준다. 화면은 `features/saved/`에 이름 칸(`SavedTitle`)과 공유 컨트롤(`ShareControls`)을 더한다.

**Tech Stack:** Spring Boot 4.1.1, Java 17, JdbcClient, Flyway, MockMvc / React, TypeScript, Tailwind v4, Phosphor icons, oxlint.

**Spec:** `docs/superpowers/specs/2026-10-09-saved-rename-share-design.md`

## Global Constraints

- Gradle 명령 앞에 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.
- package-by-feature: 백엔드는 `com.example.pickyourfood.saved`, 화면은 `frontend/src/features/saved/`만 고친다. 기능끼리 서로 import하지 않는다.
- 공유를 끈 결과를 주인이 아닌 사람이 열면 삭제된 결과와 똑같이 404, 미리보기는 기본 카드.
- 새 결과·기존 결과 모두 공유 켜짐(`default true`).
- 이름: 앞뒤 공백 제거 후 비어 있지 않고 100자 이하. `title`·`shared`가 모두 없으면 400. 남의 결과·없는 결과는 404.
- 화면은 서버가 성공을 돌려준 뒤에만 바꾼다.
- 화면 문구(그대로): `이름 바꾸기`, `이름`, `저장`, `취소`, `이름을 바꾸지 못했어요. 다시 시도해 주세요.`, `공유`, `공유가 꺼져 있어요. 다른 사람은 이 링크를 열 수 없어요.`, `공유 설정을 바꾸지 못했어요. 다시 시도해 주세요.`, `비공개`.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. `.claude/`, `.serena/`, `.superpowers/`, `graphify-out/`, `data/`는 커밋하지 않는다.

## Review Focus

1. 공백만 있는 이름을 화면에서 보내려 할 때: `저장`이 비활성이라 요청이 나가지 않아야 한다 → Task 3 헤드리스 확인 1에 포함.
2. 공유 스위치를 빠르게 두 번 누를 때: 요청 중에는 비활성이라 요청이 하나만 나가야 한다 → Task 3 헤드리스 확인 3에 포함.
3. 바꾼 이름에 HTML이 들어 있을 때: 공유 페이지에서 이스케이프되어야 한다 → Task 2 `renamedResultShowsTheNewName`에 포함.
4. 남의 결과에 PATCH: 404이고 이름·공유 상태가 바뀌지 않아야 한다 → Task 1 `onlyTheOwnerCanChangeAResult`.
5. 정확히 100자 이름은 받아들여야 한다(PostgreSQL `varchar(100)` 경계) → Task 1 `badChangesAreRejected`.

---

### Task 1: 공유 컬럼과 PATCH API

**Files:**
- Create: `src/main/resources/db/migration/V4__saved_result_shared.sql`
- Modify: `src/main/java/com/example/pickyourfood/saved/SavedRepository.java`
- Modify: `src/main/java/com/example/pickyourfood/saved/SavedResult.java`
- Modify: `src/main/java/com/example/pickyourfood/saved/SavedController.java`
- Test: `src/test/java/com/example/pickyourfood/saved/SavedControllerTest.java`

**Interfaces:**
- Produces:
  - `SavedRepository.Stored(long accountId, String title, boolean shared, String payload, Instant createdAt)`
  - `SavedRepository.Summary(String id, String title, String foodName, String originName, Instant createdAt, boolean shared)`
  - `boolean SavedRepository.update(String id, long accountId, String title, Boolean shared)` — null인 필드는 그대로. 둘 다 null로 부르지 않는다.
  - `SavedResult withTitle(String title)`
  - JSON: `GET /api/saved/{id}` → `{ id, createdAt, mine, shared, result }`, `GET /api/saved` 항목에 `shared`.
  - `PATCH /api/saved/{id}` 본문 `{ "title"?: string, "shared"?: boolean }` → 204.

- [ ] **Step 1: Write the failing tests**

`SavedControllerTest.java`에 import 두 줄을 더한다:

```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import org.springframework.test.web.servlet.ResultActions;
```

`rejects` 메서드 아래에 도우미를 더한다:

```java
	private ResultActions change(String id, String body, Cookie... session) throws Exception {
		return mvc.perform(withCsrf(patch("/api/saved/" + id), session).contentType(MediaType.APPLICATION_JSON).content(body));
	}
```

파일 끝(마지막 `}` 앞)에 테스트를 더한다:

```java
	@Test
	void theOwnerRenamesAResult() throws Exception {
		String id = save(SEOYUN, RESULT);

		change(id, "{\"title\":\"  성수 데이트  \"}", loggedIn(SEOYUN)).andExpect(status().isNoContent());

		mvc.perform(get("/api/saved/" + id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shared").value(true))
				.andExpect(jsonPath("$.result.title").value("성수 데이트"))
				.andExpect(jsonPath("$.result.best.name").value("탄탄멘"));
		mvc.perform(get("/api/saved").cookie(loggedIn(SEOYUN)))
				.andExpect(jsonPath("$[?(@.id == '%s')].title", id).value("성수 데이트"))
				.andExpect(jsonPath("$[?(@.id == '%s')].shared", id).value(true));
	}

	@Test
	void anUnsharedResultLooksDeletedToEveryoneButTheOwner() throws Exception {
		String id = save(SEOYUN, MENU_ONLY);

		change(id, "{\"shared\":false}", loggedIn(SEOYUN)).andExpect(status().isNoContent());

		mvc.perform(get("/api/saved/" + id)).andExpect(status().isNotFound());
		mvc.perform(get("/api/saved/" + id).cookie(loggedIn(HARAM))).andExpect(status().isNotFound());
		mvc.perform(get("/api/saved/" + id).cookie(loggedIn(SEOYUN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mine").value(true))
				.andExpect(jsonPath("$.shared").value(false));
		mvc.perform(get("/api/saved").cookie(loggedIn(SEOYUN)))
				.andExpect(jsonPath("$[?(@.id == '%s')].shared", id).value(false));

		change(id, "{\"shared\":true}", loggedIn(SEOYUN)).andExpect(status().isNoContent());

		mvc.perform(get("/api/saved/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.shared").value(true));
	}

	@Test
	void onlyTheOwnerCanChangeAResult() throws Exception {
		String id = save(SEOYUN, MENU_ONLY);

		mvc.perform(withCsrf(patch("/api/saved/" + id)).contentType(MediaType.APPLICATION_JSON).content("{\"shared\":false}"))
				.andExpect(status().isUnauthorized());
		change(id, "{\"title\":\"남의 것\",\"shared\":false}", loggedIn(HARAM)).andExpect(status().isNotFound());
		change("AAAAAAAAAAAAAAAAAAAAAA", "{\"shared\":false}", loggedIn(SEOYUN)).andExpect(status().isNotFound());

		mvc.perform(get("/api/saved/" + id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result.title").value("당신에게 딱 맞는 메뉴"));
	}

	@Test
	void changingNeedsTheCsrfHeader() throws Exception {
		String id = save(SEOYUN, MENU_ONLY);

		mvc.perform(patch("/api/saved/" + id).cookie(loggedIn(SEOYUN)).contentType(MediaType.APPLICATION_JSON).content("{\"shared\":false}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void badChangesAreRejected() throws Exception {
		String id = save(SEOYUN, MENU_ONLY);

		for (String body : new String[] { "{}", "{\"title\":null,\"shared\":null}", "{\"title\":\"   \"}",
				"{\"title\":\"" + "가".repeat(101) + "\"}", "{\"shared\":\"nope\"}" }) {
			change(id, body, loggedIn(SEOYUN)).andExpect(status().isBadRequest());
		}
		change(id, "{\"title\":\"" + "가".repeat(100) + "\"}", loggedIn(SEOYUN)).andExpect(status().isNoContent());
	}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.saved.SavedControllerTest'`
Expected: FAIL — the new tests get 405/403 from the missing PATCH mapping and `$.shared` does not exist; the existing tests still pass.

- [ ] **Step 3: Write the migration**

`src/main/resources/db/migration/V4__saved_result_shared.sql`:

```sql
-- every result starts shared, so links already sent keep working
alter table saved_result add column shared boolean not null default true;
```

- [ ] **Step 4: Update `SavedRepository`**

Add `import java.util.ArrayList;`. Replace `findByAccount`, `find` and the records, and add `update` after `delete`:

```java
	List<Summary> findByAccount(long accountId) {
		return jdbc.sql("select id, title, food_name, origin_name, created_at, shared from saved_result where account_id = ? order by created_at desc")
				.param(accountId)
				.query((rs, row) -> new Summary(rs.getString("id"), rs.getString("title"), rs.getString("food_name"),
						rs.getString("origin_name"), rs.getTimestamp("created_at").toInstant(), rs.getBoolean("shared")))
				.list();
	}

	Optional<Stored> find(String id) {
		return jdbc.sql("select account_id, title, shared, payload, created_at from saved_result where id = ?").param(id)
				.query((rs, row) -> new Stored(rs.getLong("account_id"), rs.getString("title"), rs.getBoolean("shared"),
						rs.getString("payload"), rs.getTimestamp("created_at").toInstant()))
				.optional();
	}
```

```java
	// a null field stays as it is (only the set ones go in the SQL, so no untyped null reaches PostgreSQL);
	// false when there is no such result or it belongs to someone else
	boolean update(String id, long accountId, String title, Boolean shared) {
		List<String> sets = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		if (title != null) {
			sets.add("title = ?");
			params.add(title);
		}
		if (shared != null) {
			sets.add("shared = ?");
			params.add(shared);
		}
		params.add(id);
		params.add(accountId);
		return jdbc.sql("update saved_result set " + String.join(", ", sets) + " where id = ? and account_id = ?")
				.params(params).update() == 1;
	}

	record Summary(String id, String title, String foodName, String originName, Instant createdAt, boolean shared) {
	}

	// title is the current name: renaming changes the column, not the payload
	record Stored(long accountId, String title, boolean shared, String payload, Instant createdAt) {
	}
```

- [ ] **Step 5: Add `withTitle` to `SavedResult`**

Inside `record SavedResult(...) {`, before `record Food`:

```java
	// the payload keeps the name it was saved with; the title column has the current one
	SavedResult withTitle(String title) {
		return new SavedResult(title, best, alternatives, places);
	}
```

- [ ] **Step 6: Update `SavedController`**

Add `import org.springframework.web.bind.annotation.PatchMapping;`. Replace `one` and add `change` after `delete`; update `View` and add `Change`:

```java
	// public: anyone with the link sees it, unless its owner turned sharing off; auth is null for visitors
	@GetMapping("/{id}")
	View one(@PathVariable String id, Authentication auth) {
		Stored stored = saved.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		boolean mine = auth != null && auth.getName().equals(String.valueOf(stored.accountId()));
		// an unshared result answers like a deleted one, so others can't tell it exists
		if (!stored.shared() && !mine) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		SavedResult result = json.readValue(stored.payload(), SavedResult.class).withTitle(stored.title());
		return new View(id, stored.createdAt(), mine, stored.shared(), result);
	}
```

```java
	// renames and/or turns the share link on or off; someone else's result answers 404, as in delete
	@PatchMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void change(@PathVariable String id, @RequestBody Change change, Authentication auth) {
		String title = change.title() == null ? null : change.title().strip();
		if ((title == null && change.shared() == null) || (title != null && (title.isEmpty() || title.length() > 100))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		}
		if (!saved.update(id, accountId(auth), title, change.shared())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
	}
```

```java
	record View(String id, Instant createdAt, boolean mine, boolean shared, SavedResult result) {
	}

	record Change(String title, Boolean shared) {
	}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.saved.*'`
Expected: PASS (all `SavedControllerTest` and `SharePageControllerTest` tests; `SharePageController` compiles unchanged because it only uses `stored.payload()`).

- [ ] **Step 8: Run the full suite**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test`
Expected: BUILD SUCCESSFUL. `PostgresMigrationTest` runs V4 on PostgreSQL when Docker is running (skipped otherwise — say which in the report).

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V4__saved_result_shared.sql src/main/java/com/example/pickyourfood/saved/ src/test/java/com/example/pickyourfood/saved/SavedControllerTest.java
git commit -m "feat: rename a saved result and turn its share link off

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 공유 미리보기 카드

**Files:**
- Modify: `src/main/java/com/example/pickyourfood/saved/SharePageController.java`
- Test: `src/test/java/com/example/pickyourfood/saved/SharePageControllerTest.java`

**Interfaces:**
- Consumes: `SavedRepository.Stored(accountId, title, shared, payload, createdAt)`, `SavedRepository.update(id, accountId, title, shared)` from Task 1.

- [ ] **Step 1: Write the failing tests**

Add to `SharePageControllerTest` (before the last `}`):

```java
	@Test
	void unsharedResultGetsTheDefaultCard() throws Exception {
		String id = share(menu("오늘의 랜덤 메뉴", "고소하고 매콤한 국물"));
		saved.update(id, OWNER, null, false);

		assertThat(page(id)).contains(
				"<title>오늘 뭐 먹지</title>",
				"<meta property=\"og:title\" content=\"오늘 뭐 먹지\" />",
				"<meta property=\"og:description\" content=\"오늘 먹을 메뉴를 골라 드려요\" />")
				.doesNotContain("탄탄멘", "고소하고 매콤한 국물");
	}

	@Test
	void renamedResultShowsTheNewName() throws Exception {
		String id = share(menu("오늘의 랜덤 메뉴", "고소하고 매콤한 국물"));
		saved.update(id, OWNER, "성수 <데이트>", null);

		assertThat(page(id)).contains(
				"<title>탄탄멘 · 성수 &lt;데이트&gt;</title>",
				"<meta property=\"og:title\" content=\"탄탄멘 · 성수 &lt;데이트&gt;\" />")
				.doesNotContain("오늘의 랜덤 메뉴", "<데이트>");
	}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.saved.SharePageControllerTest'`
Expected: FAIL — both new tests still see the payload's card (`탄탄멘 · 오늘의 랜덤 메뉴`).

- [ ] **Step 3: Update `SharePageController`**

In `page`, replace the `Card card = ...` line:

```java
		// an unshared result gets the default card, the same as a deleted one
		Card card = saved.find(id).filter(SavedRepository.Stored::shared)
				.map(stored -> card(stored.payload(), stored.title())).orElse(Card.DEFAULT);
```

Change `card`'s signature and its title use (the rest of the method stays):

```java
	// a payload that no longer reads as a SavedResult still opens the app, with the default card;
	// title is the column's, which renaming changes
	private Card card(String payload, String title) {
```

```java
		if (result == null || result.best() == null || result.best().name() == null) return Card.DEFAULT;
```

```java
		return new Card(result.best().name() + " · " + title, description);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests 'com.example.pickyourfood.saved.*'`
Expected: PASS (including the existing escape, default-card and wrong-shape tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/pickyourfood/saved/SharePageController.java src/test/java/com/example/pickyourfood/saved/SharePageControllerTest.java
git commit -m "feat: share cards use the current name and hide unshared results

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 이름 바꾸기·공유 스위치 화면

**Files:**
- Modify: `frontend/src/features/saved/api.ts`
- Create: `frontend/src/features/saved/SavedTitle.tsx`
- Create: `frontend/src/features/saved/ShareControls.tsx`
- Modify: `frontend/src/features/saved/SavedView.tsx`
- Modify: `frontend/src/features/saved/SavedList.tsx`
- Headless driver (not committed): `.superpowers/tools/saved-share.mjs`

**Interfaces:**
- Consumes: `GET /api/saved/{id}` → `shared`, `GET /api/saved` items → `shared`, `PATCH /api/saved/{id}` from Task 1.
- Produces: `updateSaved(id: string, change: { title?: string; shared?: boolean }): Promise<void>`; `SavedEntry.shared: boolean`; `SavedSummary.shared: boolean`.

- [ ] **Step 1: `api.ts`**

Add `shared: boolean` after `mine: boolean` in `SavedEntry`, and after `createdAt: string` in `SavedSummary`. After `deleteSaved`, add:

```ts
// the owner renames the result or turns its share link off and on
export function updateSaved(id: string, change: { title?: string; shared?: boolean }): Promise<void> {
  return request(`/api/saved/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(change),
  })
}
```

- [ ] **Step 2: `SavedTitle.tsx`**

```tsx
import { PencilSimple } from '@phosphor-icons/react'
import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { updateSaved } from './api.ts'

type Props = {
  id: string
  title: string
  mine: boolean
  onRenamed: (title: string) => void
}

// the result's name; its owner can rename it in place
export default function SavedTitle({ id, title, mine, onRenamed }: Props) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(title)
  const [status, setStatus] = useState<'idle' | 'busy' | 'failed'>('idle')
  const input = useRef<HTMLInputElement>(null)
  const edit = useRef<HTMLButtonElement>(null)
  const opened = useRef(false)

  // focus moves into the form when it opens and back to the button when it closes
  useEffect(() => {
    if (editing) input.current?.focus()
    else if (opened.current) edit.current?.focus()
    opened.current = editing
  }, [editing])

  function open() {
    setDraft(title)
    setStatus('idle')
    setEditing(true)
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const next = draft.trim()
    if (!next || status === 'busy') return
    setStatus('busy')
    updateSaved(id, { title: next }).then(
      () => {
        onRenamed(next)
        setStatus('idle')
        setEditing(false)
      },
      () => setStatus('failed'),
    )
  }

  if (!editing) {
    return (
      <p className="mt-2 flex items-center gap-1 text-sm text-zinc-500">
        <span className="min-w-0 break-words">{title}</span>
        {mine && (
          <button
            ref={edit}
            type="button"
            aria-label="이름 바꾸기"
            onClick={open}
            className="shrink-0 rounded-full p-2 text-zinc-500 transition hover:bg-zinc-100 hover:text-zinc-900 active:scale-[0.98]"
          >
            <PencilSimple size={16} />
          </button>
        )}
      </p>
    )
  }

  return (
    <form
      onSubmit={submit}
      onKeyDown={(event) => event.key === 'Escape' && setEditing(false)}
      className="mt-2 grid max-w-md gap-2"
    >
      <label htmlFor="saved-title" className="text-sm text-zinc-500">
        이름
      </label>
      <div className="flex flex-wrap gap-2">
        <input
          id="saved-title"
          ref={input}
          required
          maxLength={100}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          className="min-w-0 flex-1 basis-40 rounded-full border border-zinc-300 bg-white px-5 py-3 text-sm outline-none transition focus:border-accent"
        />
        <button
          type="submit"
          disabled={status === 'busy' || !draft.trim()}
          className="rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98] disabled:opacity-40"
        >
          저장
        </button>
        <button
          type="button"
          onClick={() => setEditing(false)}
          className="rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]"
        >
          취소
        </button>
      </div>
      {status === 'failed' && (
        <p role="alert" className="text-sm text-accent">
          이름을 바꾸지 못했어요. 다시 시도해 주세요.
        </p>
      )}
    </form>
  )
}
```

- [ ] **Step 3: `ShareControls.tsx`**

```tsx
import { useState } from 'react'
import { updateSaved } from './api.ts'
import ShareButton from './ShareButton.tsx'

type Props = {
  id: string
  mine: boolean
  shared: boolean
  onChange: (shared: boolean) => void
}

// visitors get the link; the owner can also turn it off, which makes the link look deleted to everyone else
export default function ShareControls({ id, mine, shared, onChange }: Props) {
  const [status, setStatus] = useState<'idle' | 'busy' | 'failed'>('idle')

  if (!mine) return <ShareButton id={id} />

  function toggle() {
    setStatus('busy')
    updateSaved(id, { shared: !shared }).then(
      () => {
        onChange(!shared)
        setStatus('idle')
      },
      () => setStatus('failed'),
    )
  }

  return (
    <>
      <button
        type="button"
        role="switch"
        aria-checked={shared}
        onClick={toggle}
        disabled={status === 'busy'}
        className="flex items-center gap-2 rounded-full border border-zinc-300 py-2.5 pl-2.5 pr-5 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98] disabled:opacity-40"
      >
        <span aria-hidden className={`flex h-6 w-10 items-center rounded-full p-0.5 transition ${shared ? 'bg-accent' : 'bg-zinc-300'}`}>
          <span className={`size-5 rounded-full bg-white shadow-sm transition ${shared ? 'translate-x-4' : ''}`} />
        </span>
        공유
      </button>
      {shared ? (
        <ShareButton id={id} />
      ) : (
        <p className="basis-full text-sm text-zinc-500">공유가 꺼져 있어요. 다른 사람은 이 링크를 열 수 없어요.</p>
      )}
      {status === 'failed' && (
        <p role="alert" className="basis-full text-sm text-accent">
          공유 설정을 바꾸지 못했어요. 다시 시도해 주세요.
        </p>
      )}
    </>
  )
}
```

- [ ] **Step 4: `SavedView.tsx`**

Imports: replace `import ShareButton from './ShareButton.tsx'` with

```tsx
import SavedTitle from './SavedTitle.tsx'
import ShareControls from './ShareControls.tsx'
```

After `remove()`, add:

```tsx
  // applied only after the server took the change
  function update(change: (entry: SavedEntry) => SavedEntry) {
    setState((prev) => (prev.status === 'done' ? { status: 'done', entry: change(prev.entry) } : prev))
  }
```

Replace `<p className="mt-2 text-sm text-zinc-500">{entry.result.title}</p>` with

```tsx
          <SavedTitle
            id={entry.id}
            title={entry.result.title}
            mine={entry.mine}
            onRenamed={(title) => update((e) => ({ ...e, result: { ...e.result, title } }))}
          />
```

Replace `<ShareButton id={entry.id} />` with

```tsx
            <ShareControls
              id={entry.id}
              mine={entry.mine}
              shared={entry.shared}
              onChange={(shared) => update((e) => ({ ...e, shared }))}
            />
```

- [ ] **Step 5: `SavedList.tsx`**

Import `LockSimple` with the other icons: `import { House, LockSimple, SignIn, Trash } from '@phosphor-icons/react'`. Replace the second line of the open button

```tsx
                <span className="mt-1 block truncate text-sm text-zinc-500">
                  {[item.title, item.originName && `${item.originName} 기준`].filter(Boolean).join(' · ')}
                </span>
```

with

```tsx
                <span className="mt-1 flex min-w-0 items-center gap-2 text-sm text-zinc-500">
                  {!item.shared && (
                    <span className="flex shrink-0 items-center gap-1 font-medium text-zinc-700">
                      <LockSimple size={14} /> 비공개
                    </span>
                  )}
                  <span className="truncate">
                    {[item.title, item.originName && `${item.originName} 기준`].filter(Boolean).join(' · ')}
                  </span>
                </span>
```

- [ ] **Step 6: Build and lint**

Run: `cd frontend && npm run build && npm run lint`
Expected: build succeeds, lint reports 0 errors (the existing warning count is unchanged).

- [ ] **Step 7: Headless checks**

Start Vite (`cd frontend && npx vite --port 5174 --strictPort`, background) and Chrome (`"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --remote-debugging-port=9222 --user-data-dir=/Users/limdongju/.claude/jobs/457a919a/tmp/chrome-h3 about:blank`, background). Write `.superpowers/tools/saved-share.mjs` by copying the CDP plumbing (`jsonGet`, `connect`, `makeClient`, `Fetch.enable` + `Fetch.requestPaused` → `Fetch.fulfillRequest`, `assert`, `Page.captureScreenshot`) from `.superpowers/tools/place-map.mjs`. It intercepts `http://localhost:5174/api/*` and answers:
- `GET /api/saved/mine1` → `{ id: 'mine1', createdAt: '2026-10-09T03:00:00Z', mine: true, shared: true, result: { title: '오늘의 랜덤 메뉴', best: { id: 'tantanmen', name: '탄탄멘', description: '고소하고 매콤한 국물' }, alternatives: [], places: null } }`
- `GET /api/saved/theirs1` → the same with `id: 'theirs1', mine: false`
- `GET /api/saved` → two summaries, one `shared: true`, one `shared: false`
- `GET /api/me` (or whatever the app calls to know the account; read `features/account/api.ts`) → a logged-in account
- `PATCH /api/saved/mine1` → 204, or 500 while `server.patchFails` is true; logs every PATCH body; delays 300 ms so the busy state is visible.

Open `http://localhost:5174/s/mine1` and check, printing PASS/FAIL per line:
1. `이름 바꾸기` → the input holds `오늘의 랜덤 메뉴` and has focus; clearing it to `   ` disables `저장`; typing `성수 데이트` and `저장` → one PATCH with body `{"title":"성수 데이트"}`, the form closes, the page shows `성수 데이트`, focus is on `이름 바꾸기`.
2. With `server.patchFails`: rename → the form stays, `[role=alert]` has `이름을 바꾸지 못했어요. 다시 시도해 주세요.`; Escape closes the form and the old name remains.
3. With PATCH ok: the `[role=switch]` has `aria-checked="true"`; two quick clicks send exactly one PATCH (`{"shared":false}`); afterwards `aria-checked="false"`, no `링크 공유` button, the text `공유가 꺼져 있어요. 다른 사람은 이 링크를 열 수 없어요.` is shown. Clicking again turns it back on and `링크 공유` returns.
4. With `server.patchFails`: the switch click leaves `aria-checked` unchanged and shows `공유 설정을 바꾸지 못했어요. 다시 시도해 주세요.`.
5. `/s/theirs1`: no `[role=switch]`, no `이름 바꾸기`, `링크 공유` is there.
6. The saved list (open it the way the app does; read `App.tsx` for its path or button) shows `비공개` on the unshared row only.
7. At 375px width (`Emulation.setDeviceMetricsOverride`), on `/s/mine1` with the rename form open and with sharing off, `document.documentElement.scrollWidth <= 375`; save screenshots to `/Users/limdongju/.claude/jobs/457a919a/tmp/h3-ui/`.

Expected: every line PASS. Stop Vite and Chrome afterwards.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/features/saved/
git commit -m "feat: rename a saved result and switch its sharing from the result page

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
