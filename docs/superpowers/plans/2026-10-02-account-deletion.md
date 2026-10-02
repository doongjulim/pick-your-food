# F. 회원 탈퇴 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그인한 사용자가 확인 창 한 번으로 탈퇴하면 카카오 연결을 끊고, 계정·저장 결과·모든 세션을 지운다.

**Architecture:** `account` 기능에 카카오 `unlink` 클라이언트(`KakaoUnlink`, 앱 Admin 키)를 두고 `DELETE /api/me`가 unlink → (한 트랜잭션) 저장 결과·계정 삭제 → 그 계정의 모든 세션 삭제 순서로 처리한다. unlink 실패는 502이고 아무것도 지우지 않는다. 화면은 `account` 기능 안의 `<dialog>` 확인 창.

**Tech Stack:** Spring Boot 4.1.1 (Java 17, `RestClient`, `JdbcClient`, Spring Session JDBC, Spring Security 7), React + Vite + Tailwind v4, Phosphor icons.

**Spec:** `docs/superpowers/specs/2026-10-02-account-deletion-design.md`

## Global Constraints

- 모든 Gradle 명령 앞에 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` (기본 JDK는 11).
- Package by feature: 백엔드 `com.example.pickyourfood.account`, 프론트엔드 `frontend/src/features/account/`. `account`는 `saved`·`place` 코드를 import하지 않는다. 두 기능 이상이 쓰는 코드는 `food`(백엔드)에 둔다.
- 새 의존성 없음(백엔드·프론트엔드 모두).
- 커밋하지 않는 경로: `.claude/`, `.serena/`, `graphify-out/`, `data/`, `.superpowers/`.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- 문구(그대로): 메뉴 `회원 탈퇴` · 제목 `정말 탈퇴할까요?` · 본문 `저장한 결과와 공유 링크가 모두 사라지고 되돌릴 수 없어요. 카카오 계정 연결도 끊어져요.` · 버튼 `취소` / `탈퇴하기` / 진행 중 `탈퇴하는 중…` · 실패 `탈퇴하지 못했어요. 다시 시도해 주세요.` · 성공 `탈퇴했어요. 그동안 고마웠어요.`
- 설정 키: `kakao.admin-key=${KAKAO_ADMIN_KEY:}`. 키가 비어 있으면 카카오 호출을 건너뛴다.

## Review Focus

1. 카카오 호출이 실패했을 때 DB나 세션이 하나라도 지워지면 안 된다 → Task 2 `failedKakaoUnlinkDeletesNothing`.
2. 다른 기기에 남은 같은 계정의 세션이 계속 로그인 상태면 안 된다 → Task 2 `deletingTheAccountRemovesItsDataAndEverySession`(두 번째 세션).
3. 다른 계정의 저장 결과가 함께 지워지면 안 된다 → Task 2 같은 테스트.
4. 사용자가 카카오에서 먼저 연결을 끊은 경우(`-101`) 탈퇴가 막히면 안 된다 → Task 1 `alreadyUnlinkedUserIsFine`.
5. 이미 지워진 계정의 세션(재시도·동시 탈퇴)은 500이 아니라 204로 끝나야 한다 → Task 2 `alreadyDeletedAccountJustEndsTheSession`.

---

### Task 1: 카카오 연결 끊기 클라이언트

**Files:**
- Move: `src/main/java/com/example/pickyourfood/place/HttpTimeouts.java` → `src/main/java/com/example/pickyourfood/food/HttpTimeouts.java`
- Modify: `src/main/java/com/example/pickyourfood/place/KakaoClient.java` (import 한 줄)
- Modify: `src/main/java/com/example/pickyourfood/place/GooglePlacesClient.java` (import 한 줄)
- Create: `src/main/java/com/example/pickyourfood/account/KakaoUnlink.java`
- Test: `src/test/java/com/example/pickyourfood/account/KakaoUnlinkTest.java`
- Modify: `src/main/resources/application.properties`, `render.yaml`, `README.md`

**Interfaces:**
- Produces: `com.example.pickyourfood.food.HttpTimeouts.factory()` (public); `account.KakaoUnlink` (package-private `@Component`) with `void unlink(String kakaoId)` — throws `RestClientException` on failure, returns normally on success, on Kakao `-101`, or when the key is blank.

- [ ] **Step 1: Move `HttpTimeouts` to `food`**

```bash
git mv src/main/java/com/example/pickyourfood/place/HttpTimeouts.java src/main/java/com/example/pickyourfood/food/HttpTimeouts.java
```

Replace the file's content with:

```java
package com.example.pickyourfood.food;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

// timeouts for calls to outside APIs (Kakao, Google); a slow API fails fast instead of holding the request
public final class HttpTimeouts {

	private HttpTimeouts() {
	}

	public static SimpleClientHttpRequestFactory factory() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(2));
		factory.setReadTimeout(Duration.ofSeconds(3));
		return factory;
	}
}
```

In `place/KakaoClient.java` and `place/GooglePlacesClient.java` add `import com.example.pickyourfood.food.HttpTimeouts;` in sorted position (first import after the `package` line, since `com.` sorts before `java.`/`org.` — keep whatever order the file uses).

- [ ] **Step 2: Write the failing test**

`src/test/java/com/example/pickyourfood/account/KakaoUnlinkTest.java`:

```java
package com.example.pickyourfood.account;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class KakaoUnlinkTest {

	private static final String URL = "https://kapi.kakao.com/v1/user/unlink";

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final KakaoUnlink kakao = new KakaoUnlink(builder, "test-admin");

	@Test
	void unlinksTheUserWithTheAdminKey() {
		server.expect(requestTo(URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "KakaoAK test-admin"))
				.andExpect(content().formDataContains(Map.of("target_id_type", "user_id", "target_id", "12345")))
				.andRespond(withSuccess("{\"id\":12345}", MediaType.APPLICATION_JSON));

		kakao.unlink("12345");

		server.verify();
	}

	@Test
	void alreadyUnlinkedUserIsFine() {
		server.expect(requestTo(URL)).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
				.body("{\"msg\":\"NotRegisteredUserException\",\"code\":-101}"));

		assertThatCode(() -> kakao.unlink("12345")).doesNotThrowAnyException();
	}

	@Test
	void otherBadRequestsFail() {
		server.expect(requestTo(URL)).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
				.body("{\"msg\":\"ipmismatched\",\"code\":-2}"));

		assertThatThrownBy(() -> kakao.unlink("12345")).isInstanceOf(HttpClientErrorException.class);
	}

	@Test
	void kakaoErrorsFail() {
		server.expect(requestTo(URL)).andRespond(withServerError());

		assertThatThrownBy(() -> kakao.unlink("12345")).isInstanceOf(RestClientException.class);
	}

	@Test
	void withoutAKeyNothingIsSent() {
		new KakaoUnlink(builder, "").unlink("12345");

		server.verify();
	}
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew test --tests '*KakaoUnlinkTest'`
Expected: compilation FAIL — `cannot find symbol: class KakaoUnlink`.

- [ ] **Step 4: Write the implementation**

`src/main/java/com/example/pickyourfood/account/KakaoUnlink.java`:

```java
package com.example.pickyourfood.account;

import com.example.pickyourfood.food.HttpTimeouts;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

// disconnects a Kakao user from the app with the app's admin key, so it works long after the login
@Component
class KakaoUnlink {

	// Kakao's answer for a user that is no longer linked to the app
	private static final Pattern NOT_LINKED = Pattern.compile("\"code\"\\s*:\\s*-101\\b");

	private final RestClient http;
	private final String key;

	@Autowired
	KakaoUnlink(@Value("${kakao.admin-key:}") String key) {
		this(RestClient.builder().requestFactory(HttpTimeouts.factory()), key);
	}

	KakaoUnlink(RestClient.Builder builder, String key) {
		this.http = builder.baseUrl("https://kapi.kakao.com").build();
		this.key = key;
	}

	// skipped without an admin key (local development); other failures propagate as RestClientException
	void unlink(String kakaoId) {
		if (key.isBlank()) return;
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("target_id_type", "user_id");
		form.add("target_id", kakaoId);
		try {
			http.post().uri("/v1/user/unlink")
					.header(HttpHeaders.AUTHORIZATION, "KakaoAK " + key)
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(form)
					.retrieve()
					.toBodilessEntity();
		} catch (HttpClientErrorException.BadRequest e) {
			if (!NOT_LINKED.matcher(e.getResponseBodyAsString()).find()) throw e;
		}
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew test --tests '*KakaoUnlinkTest' --tests '*KakaoClientTest' --tests '*GooglePlacesClientTest'`
Expected: PASS (5 new + existing place client tests).

- [ ] **Step 6: Configuration and docs**

`src/main/resources/application.properties`, after the line `kakao.rest-key=${KAKAO_REST_KEY:}`:

```properties
kakao.admin-key=${KAKAO_ADMIN_KEY:}
```

`render.yaml`, after the `KAKAO_CLIENT_SECRET` entry (same indentation):

```yaml
      - key: KAKAO_ADMIN_KEY
        sync: false
```

`README.md`:
- Line 12 (키 목록) — append: `` `KAKAO_ADMIN_KEY`(회원 탈퇴 시 카카오 연결 끊기, 없으면 건너뜀). ``
- Deploy step 3, after the `KAKAO_REST_KEY, ...` bullet, add a bullet:
  `` - `KAKAO_ADMIN_KEY`: 카카오 개발자 콘솔 → 앱 → 앱 키 → Admin 키. 앱 전체 권한을 가진 키라 서버 환경변수에만 둔다 ``

- [ ] **Step 7: Full suite and commit**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew test`
Expected: BUILD SUCCESSFUL (86 existing + 5 new).

```bash
git add src/main/java/com/example/pickyourfood/food/HttpTimeouts.java src/main/java/com/example/pickyourfood/place src/main/java/com/example/pickyourfood/account/KakaoUnlink.java src/test/java/com/example/pickyourfood/account/KakaoUnlinkTest.java src/main/resources/application.properties render.yaml README.md
git commit -m "feat: add the Kakao unlink client for account deletion

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `DELETE /api/me`

**Files:**
- Modify: `src/main/java/com/example/pickyourfood/account/AccountRepository.java`
- Modify: `src/main/java/com/example/pickyourfood/account/AccountService.java`
- Modify: `src/main/java/com/example/pickyourfood/account/AccountController.java`
- Test: `src/test/java/com/example/pickyourfood/account/AccountControllerTest.java` (the spec names the tests `AccountDeletionTest`; they go here to reuse this class's session helpers)
- Test: `src/test/java/com/example/pickyourfood/account/PostgresMigrationTest.java`

**Interfaces:**
- Consumes: `KakaoUnlink.unlink(String kakaoId)` from Task 1.
- Produces: `DELETE /api/me` → 204 (deleted, or account already gone), 502 (Kakao unlink failed, nothing deleted), 401 (no login), 403 (no CSRF token). Task 3's frontend calls it.

- [ ] **Step 1: Write the failing tests**

In `AccountControllerTest.java` add imports:

```java
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
```

Add fields and helpers to the class (next to the existing ones):

```java
	@MockitoBean
	KakaoUnlink unlink;

	@Autowired
	AccountService accounts;

	@Autowired
	JdbcClient jdbc;

	private void saveResult(String id, long accountId) {
		jdbc.sql("insert into saved_result (id, account_id, title, food_name, payload, created_at) "
				+ "values (?, ?, '오늘의 랜덤 메뉴', '탄탄멘', '{}', current_timestamp)").params(id, accountId).update();
	}

	private long count(String sql, long id) {
		return jdbc.sql(sql).param(id).query(Long.class).single();
	}

	// DELETE /api/me with a valid CSRF token, as the frontend sends it
	private ResultActions deleteMe(Cookie session) throws Exception {
		Cookie xsrf = xsrfCookie();
		return mvc.perform(delete("/api/me").cookie(session, xsrf).header("X-XSRF-TOKEN", xsrf.getValue()));
	}
```

Add the tests:

```java
	@Test
	void deletingTheAccountRemovesItsDataAndEverySession() throws Exception {
		Account me = accounts.signIn("delete-me", "서윤");
		Account other = accounts.signIn("delete-other", "하람");
		saveResult("del-mine", me.id());
		saveResult("del-other", other.id());
		Cookie phone = cookieFor(loggedIn(me.id(), "서윤"));
		Cookie laptop = cookieFor(loggedIn(me.id(), "서윤"));

		deleteMe(phone).andExpect(status().isNoContent());

		verify(unlink).unlink("delete-me");
		assertThat(count("select count(*) from account where id = ?", me.id())).isZero();
		assertThat(count("select count(*) from saved_result where account_id = ?", me.id())).isZero();
		assertThat(count("select count(*) from saved_result where account_id = ?", other.id())).isEqualTo(1);
		mvc.perform(get("/api/me").cookie(phone)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me").cookie(laptop)).andExpect(status().isUnauthorized());
	}

	@Test
	void failedKakaoUnlinkDeletesNothing() throws Exception {
		Account me = accounts.signIn("delete-fails", "서윤");
		saveResult("del-fails", me.id());
		Cookie session = cookieFor(loggedIn(me.id(), "서윤"));
		doThrow(new ResourceAccessException("Kakao is down")).when(unlink).unlink(anyString());

		deleteMe(session).andExpect(status().isBadGateway());

		assertThat(count("select count(*) from account where id = ?", me.id())).isEqualTo(1);
		assertThat(count("select count(*) from saved_result where account_id = ?", me.id())).isEqualTo(1);
		mvc.perform(get("/api/me").cookie(session)).andExpect(status().isOk());
	}

	@Test
	void deletingNeedsTheCsrfToken() throws Exception {
		Account me = accounts.signIn("delete-no-csrf", "서윤");
		Cookie session = cookieFor(loggedIn(me.id(), "서윤"));

		mvc.perform(delete("/api/me").cookie(session)).andExpect(status().isForbidden());

		verifyNoInteractions(unlink);
		assertThat(count("select count(*) from account where id = ?", me.id())).isEqualTo(1);
	}

	@Test
	void deletingNeedsALogin() throws Exception {
		Cookie xsrf = xsrfCookie();

		mvc.perform(delete("/api/me").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(unlink);
	}

	@Test
	void alreadyDeletedAccountJustEndsTheSession() throws Exception {
		Cookie session = cookieFor(loggedIn(987654, "서윤"));

		deleteMe(session).andExpect(status().isNoContent());

		verifyNoInteractions(unlink);
		mvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
	}
```

Also import `org.springframework.test.web.servlet.ResultActions`. If the class already has a field named `accounts` or `jdbc`, reuse it instead of adding a second one.

- [ ] **Step 2: Run them to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew test --tests '*AccountControllerTest'`
Expected: the five new tests FAIL (DELETE `/api/me` has no handler: 405 / 500 instead of 204/502), except `deletingNeedsTheCsrfToken` and `deletingNeedsALogin`, which may already pass because security answers before the handler. Existing tests pass.

- [ ] **Step 3: Repository**

Add to `AccountRepository.java` (imports `org.springframework.transaction.annotation.Transactional`):

```java
	Optional<String> kakaoId(long id) {
		return jdbc.sql("select kakao_id from account where id = ?").param(id).query(String.class).optional();
	}

	// saved results first: they reference the account
	@Transactional
	void delete(long id) {
		jdbc.sql("delete from saved_result where account_id = ?").param(id).update();
		jdbc.sql("delete from account where id = ?").param(id).update();
	}
```

- [ ] **Step 4: Service**

In `AccountService.java`:
- imports: `org.springframework.session.FindByIndexNameSessionRepository`, `org.springframework.session.Session`.
- fields and constructor:

```java
	private final AccountRepository accounts;
	private final KakaoUnlink unlink;
	private final FindByIndexNameSessionRepository<? extends Session> sessions;
	private final DefaultOAuth2UserService kakao = new DefaultOAuth2UserService();

	AccountService(AccountRepository accounts, KakaoUnlink unlink, FindByIndexNameSessionRepository<? extends Session> sessions) {
		this.accounts = accounts;
		this.unlink = unlink;
		this.sessions = sessions;
	}
```

- method (after `signIn`):

```java
	// Kakao first, so a failed call deletes nothing and can be retried; then the account,
	// its saved results and every session it is logged in with (the principal name is the account id)
	void delete(long accountId) {
		accounts.kakaoId(accountId).ifPresent(kakaoId -> {
			unlink.unlink(kakaoId);
			accounts.delete(accountId);
		});
		sessions.findByPrincipalName(String.valueOf(accountId)).keySet().forEach(sessions::deleteById);
	}
```

If startup fails with a circular reference involving `SecurityConfig` → `AccountService` → the session repository, annotate the `sessions` constructor parameter with `@org.springframework.context.annotation.Lazy` and ledger it.

- [ ] **Step 5: Controller**

Replace `AccountController.java` with:

```java
package com.example.pickyourfood.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AccountController {

	private final AccountService accounts;

	AccountController(AccountService accounts) {
		this.accounts = accounts;
	}

	// only reached when logged in; SecurityConfig answers 401 otherwise
	@GetMapping("/api/me")
	Me me(@AuthenticationPrincipal OAuth2User user) {
		return new Me(user.getAttribute(AccountService.ACCOUNT_ID), user.getAttribute(AccountService.NICKNAME));
	}

	@DeleteMapping("/api/me")
	ResponseEntity<Void> delete(@AuthenticationPrincipal OAuth2User user, Authentication authentication,
			HttpServletRequest request, HttpServletResponse response) {
		try {
			accounts.delete(user.<Long>getAttribute(AccountService.ACCOUNT_ID));
		} catch (RestClientException e) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Kakao unlink failed", e);
		}
		new SecurityContextLogoutHandler().logout(request, response, authentication);
		return ResponseEntity.noContent().build();
	}

	record Me(long id, String nickname) {
	}
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew test --tests '*AccountControllerTest' --tests '*AccountServiceTest'`
Expected: PASS.

- [ ] **Step 7: PostgreSQL check**

In `PostgresMigrationTest.java`:
- add field `@MockitoBean KakaoUnlink unlink;` (import `org.springframework.test.context.bean.override.mockito.MockitoBean`) so a developer's real `KAKAO_ADMIN_KEY` is never used.
- at the end of `accountsSessionsAndSavedResultsWork`, after the last line, append:

```java
		// account deletion removes saved results before the account they reference
		mvc.perform(post("/api/saved").cookie(session, xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"오늘의 랜덤 메뉴","best":{"id":"tantanmen","name":"탄탄멘","description":"고소하고 매콤한 국물"},"alternatives":[]}"""))
				.andExpect(status().isCreated());
		mvc.perform(delete("/api/me").cookie(session, xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
				.andExpect(status().isNoContent());
		assertThat(jdbc.sql("select count(*) from account where id = ?").param(account.id()).query(Long.class).single()).isZero();
		mvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
```

Docker Desktop must be running for this test to execute (it is skipped otherwise). Check it actually ran: `grep -c testcase build/test-results/test/TEST-com.example.pickyourfood.account.PostgresMigrationTest.xml` and confirm no `<skipped` in that file. If Docker is not available, say so in the report.

- [ ] **Step 8: Full suite and commit**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew test --rerun-tasks`
Expected: BUILD SUCCESSFUL, 0 failures (86 + 5 from Task 1 + 5 here).

```bash
git add src/main/java/com/example/pickyourfood/account src/test/java/com/example/pickyourfood/account
git commit -m "feat: let a member delete their account

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 탈퇴 확인 화면

**Files:**
- Modify: `frontend/src/features/account/api.ts`
- Create: `frontend/src/features/account/DeleteAccountDialog.tsx`
- Modify: `frontend/src/features/account/AccountMenu.tsx`

**Interfaces:**
- Consumes: `DELETE /api/me` from Task 2 (204 success; any error status means nothing was deleted).
- Produces: none.

There is no frontend test runner in this repo; the gate is `npm run build` + `npm run lint` plus the browser check in Step 5.

- [ ] **Step 1: API call**

Append to `frontend/src/features/account/api.ts`:

```ts
export function deleteAccount(): Promise<void> {
  return request('/api/me', { method: 'DELETE' })
}
```

- [ ] **Step 2: Dialog**

`frontend/src/features/account/DeleteAccountDialog.tsx`:

```tsx
import { useEffect, useRef, useState } from 'react'
import { deleteAccount } from './api.ts'

// the browser's modal dialog traps focus and closes on Esc; Esc is ignored while the deletion runs
export default function DeleteAccountDialog({ onClose, onDeleted }: { onClose: () => void; onDeleted: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [deleting, setDeleting] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    dialog.current?.showModal()
  }, [])

  function confirm() {
    setDeleting(true)
    setFailed(false)
    deleteAccount().then(onDeleted, () => {
      setDeleting(false)
      setFailed(true)
    })
  }

  return (
    <dialog
      ref={dialog}
      aria-labelledby="delete-account-title"
      onCancel={(event) => {
        event.preventDefault()
        if (!deleting) onClose()
      }}
      className="m-auto w-[min(26rem,calc(100%-2rem))] rounded-3xl border border-zinc-200 bg-white p-8 text-zinc-900 backdrop:bg-zinc-950/40"
    >
      <h2 id="delete-account-title" className="text-xl font-semibold tracking-tight">
        정말 탈퇴할까요?
      </h2>
      <p className="mt-3 text-sm leading-relaxed text-zinc-600">
        저장한 결과와 공유 링크가 모두 사라지고 되돌릴 수 없어요. 카카오 계정 연결도 끊어져요.
      </p>
      {failed && (
        <p role="alert" className="mt-4 text-sm text-accent">
          탈퇴하지 못했어요. 다시 시도해 주세요.
        </p>
      )}
      <div className="mt-8 flex justify-end gap-2">
        <button
          type="button"
          disabled={deleting}
          onClick={onClose}
          className="rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98] disabled:opacity-40"
        >
          취소
        </button>
        <button
          type="button"
          disabled={deleting}
          onClick={confirm}
          className="rounded-full bg-accent px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98] disabled:opacity-60"
        >
          {deleting ? '탈퇴하는 중…' : '탈퇴하기'}
        </button>
      </div>
    </dialog>
  )
}
```

- [ ] **Step 3: Menu**

In `frontend/src/features/account/AccountMenu.tsx`:

1. Imports: add `UserMinus` to the `@phosphor-icons/react` import (alphabetical: `BookmarksSimple, CaretDown, SignIn, SignOut, UserMinus`) and `import DeleteAccountDialog from './DeleteAccountDialog.tsx'` after the `./api.ts` imports.
2. State, after `logoutFailed`:

```tsx
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [deleted, setDeleted] = useState(false)
```

3. Inside the dropdown `motion.div`, after the 로그아웃 button:

```tsx
                <button
                  type="button"
                  onClick={() => {
                    setOpen(false)
                    setConfirmingDelete(true)
                  }}
                  className="flex w-full items-center gap-2 whitespace-nowrap rounded-xl px-4 py-2 text-sm text-zinc-400 transition hover:bg-zinc-100 hover:text-zinc-700 active:scale-[0.98]"
                >
                  <UserMinus size={16} />
                  회원 탈퇴
                </button>
```

4. After the `logoutFailed` paragraph, before the outer closing `</div>`:

```tsx
      {deleted && account.status === 'out' && (
        <p role="status" className="mt-2 text-sm text-zinc-600">
          탈퇴했어요. 그동안 고마웠어요.
        </p>
      )}
      {confirmingDelete && (
        <DeleteAccountDialog
          onClose={() => setConfirmingDelete(false)}
          onDeleted={() => {
            setConfirmingDelete(false)
            setDeleted(true)
            setAccount({ status: 'out' })
          }}
        />
      )}
```

- [ ] **Step 4: Build and lint**

Run: `cd frontend && npm run build && npm run lint`
Expected: both succeed with no errors.

- [ ] **Step 5: Browser check (throwaway, nothing committed)**

Run `cd frontend && npm run dev` (Vite on `localhost:5173`; the backend need not run). Drive headless Chrome (`/Applications/Google Chrome.app/Contents/MacOS/Google Chrome --headless=new --remote-debugging-port=9222`) with a throwaway Node 22 script in the session scratchpad that uses the DevTools protocol (built-in `WebSocket`, `Fetch.enable` with `urlPattern` `*/api/*`) to answer:
- `GET /api/me` → 200 `{"id":1,"nickname":"서윤"}` (then, after a successful delete, 401)
- `DELETE /api/me` → 204 in the success run, 502 in the failure run

Click through 닉네임 → 회원 탈퇴 → 탈퇴하기 and save screenshots (390×844 viewport) of: the open menu, the open dialog, the failure message inside the dialog (502 run), and the logged-out menu with `탈퇴했어요. 그동안 고마웠어요.` (204 run). Also confirm that pressing Escape closes the dialog when idle. Look at every screenshot. Report the screenshot paths.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/account
git commit -m "feat: add the account deletion confirmation

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
