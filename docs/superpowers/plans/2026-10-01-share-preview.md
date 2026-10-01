# 공유 링크 미리보기 (E) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/s/{id}` 응답 HTML에 그 저장 결과의 Open Graph 메타 태그를 넣어, 메신저에 붙인 공유 링크가 카드로 보이게 한다.

**Architecture:** `saved` 기능에 `SharePageController`를 두고, classpath의 빌드된 `static/index.html`을 읽어 `<head>` 뒤에 메타 태그를 끼워 넣고 `<title>`을 바꿔 돌려준다. `web.SpaController`는 `/saved`만 맡는다. 카드 이미지는 고정 PNG 한 장(`frontend/public/og.png`).

**Tech Stack:** Spring Boot 4.1.1 · Java 17 · Spring MVC · `HtmlUtils` · `ServletUriComponentsBuilder` · JUnit 5 + MockMvc · Vite(`public/` 복사) · 헤드리스 Chrome(이미지 캡처)

**Spec:** `docs/superpowers/specs/2026-10-01-share-preview-design.md`

## Global Constraints

- Gradle 명령은 모두 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` 뒤에 실행한다.
- 새 의존성 없음. 프론트엔드 코드(`frontend/src`) 변경 없음.
- 기능끼리 import하지 않는다: `SharePageController`는 `saved` 안의 것만 쓴다.
- 기본 제목 `오늘 뭐 먹지`, 기본 설명 `오늘 먹을 메뉴를 골라 드려요`, 맛집 문구 ` · {출발지} 근처 맛집과 데이트 코스`, 설명 최대 150자 + `…`.
- 메타: `og:type=website`, `og:site_name=오늘 뭐 먹지`, `og:title`, `og:description`, `og:image`(`…/og.png`), `og:url`, `twitter:card=summary_large_image`.
- 값은 `HtmlUtils.htmlEscape(value, "UTF-8")`로 이스케이프한다(한 인자 버전은 `·`·`…`를 `&middot;`·`&hellip;`로 바꾼다).
- 커밋하지 않는 것: `.claude/`, `.serena/`, `graphify-out/`, `data/`, `.superpowers/`.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. 저장된 payload가 `SavedResult`로 읽히지 않을 때(옛 형식·손상) → 500이 아니라 기본 카드와 앱 화면. (Task 1 `unreadableResultGetsTheDefaultCard`)
2. 대표 메뉴 설명이 `null`이고 맛집도 없을 때 → `null`이 글자로 찍히지 않고 기본 설명. (Task 1 `missingDescriptionFallsBackToTheDefault`)
3. 제목에 `"`·`<`·`&`가 들어 있을 때 → 속성·`<title>`을 깨지 않는다. (Task 1 `userTextIsEscaped`)
4. 운영 프록시 뒤 → `og:url`·`og:image`가 `https://` 공개 주소. (Task 1 `ProdProfileTest.sharePreviewLinksUseTheAddressThePublicSees`)
5. 실제 Vite 빌드 `index.html`(스크립트·CSS 링크 포함)에 끼워 넣어도 앱이 그대로 뜬다. (Task 3 Docker 확인 + 스크린샷)

---

### Task 1: `/s/{id}`에 카드 메타 넣기

**Files:**
- Create: `src/main/java/com/example/pickyourfood/saved/SharePageController.java`
- Create: `src/test/java/com/example/pickyourfood/saved/SharePageControllerTest.java`
- Create: `src/test/resources/static/index.html`
- Modify: `src/main/java/com/example/pickyourfood/web/SpaController.java`
- Modify: `src/test/java/com/example/pickyourfood/web/SpaControllerTest.java`
- Modify: `src/test/java/com/example/pickyourfood/account/ProdProfileTest.java`

**Interfaces:**
- Consumes: `SavedRepository.create(long accountId, SavedResult result, String payload)`, `SavedRepository.find(String id): Optional<Stored>`, `Stored.payload()`, `SavedResult(title, best, alternatives, places)`, `SavedResult.Food(id, name, description)`, `SavedResult.Places(origin, nearby, famous, dateCourses)`, `SavedResult.Origin(name, lat, lng)`.
- Produces: `GET /s/{id}` → 200 `text/html;charset=UTF-8`.

- [ ] **Step 1: 테스트용 가짜 `index.html`**

`src/test/resources/static/index.html` (실제 프론트엔드 빌드는 테스트 classpath에 없다):

```html
<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <title>오늘 뭐 먹지</title>
    <script type="module" src="/assets/index.js"></script>
  </head>
  <body>
    <div id="root"></div>
  </body>
</html>
```

- [ ] **Step 2: 실패하는 테스트**

`src/test/java/com/example/pickyourfood/saved/SharePageControllerTest.java`:

```java
package com.example.pickyourfood.saved;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class SharePageControllerTest {

	private static final long OWNER = 2001;

	@Autowired
	MockMvc mvc;

	@Autowired
	SavedRepository saved;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void account() {
		jdbc.sql("merge into account (id, kakao_id, nickname, created_at) key (id) values (?, ?, ?, ?)")
				.params(OWNER, "test-" + OWNER, "서윤", Timestamp.from(Instant.now())).update();
	}

	private String share(SavedResult result) {
		return saved.create(OWNER, result, json.writeValueAsString(result));
	}

	private static SavedResult menu(String title, String description) {
		return new SavedResult(title, new SavedResult.Food("tantanmen", "탄탄멘", description), List.of(), null);
	}

	private String page(String id) throws Exception {
		return mvc.perform(get("/s/" + id))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	void sharedResultFillsTheCard() throws Exception {
		String id = share(menu("오늘의 랜덤 메뉴", "고소하고 매콤한 국물"));

		assertThat(page(id)).contains(
				"<title>탄탄멘 · 오늘의 랜덤 메뉴</title>",
				"<meta property=\"og:title\" content=\"탄탄멘 · 오늘의 랜덤 메뉴\" />",
				"<meta property=\"og:description\" content=\"고소하고 매콤한 국물\" />",
				"<meta property=\"og:image\" content=\"http://localhost/og.png\" />",
				"<meta property=\"og:url\" content=\"http://localhost/s/" + id + "\" />",
				"<meta property=\"og:type\" content=\"website\" />",
				"<meta property=\"og:site_name\" content=\"오늘 뭐 먹지\" />",
				"<meta name=\"twitter:card\" content=\"summary_large_image\" />",
				"<script type=\"module\" src=\"/assets/index.js\"></script>",
				"<div id=\"root\"></div>")
				.doesNotContain("<title>오늘 뭐 먹지</title>");
	}

	@Test
	void resultsWithPlacesMentionTheOrigin() throws Exception {
		var places = new SavedResult.Places(new SavedResult.Origin("성수동", 37.54, 127.05), List.of(), List.of(), List.of());
		String id = share(new SavedResult("오늘의 랜덤 메뉴", new SavedResult.Food("tantanmen", "탄탄멘", "고소하고 매콤한 국물"), List.of(), places));

		assertThat(page(id)).contains("<meta property=\"og:description\" content=\"고소하고 매콤한 국물 · 성수동 근처 맛집과 데이트 코스\" />");
	}

	@Test
	void userTextIsEscaped() throws Exception {
		String id = share(menu("\"><script>alert(1)</script>", "a & b"));

		assertThat(page(id))
				.doesNotContain("<script>alert(1)")
				.contains("<title>탄탄멘 · &quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;</title>",
						"content=\"탄탄멘 · &quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;\"",
						"content=\"a &amp; b\"");
	}

	@Test
	void longDescriptionsAreCut() throws Exception {
		String id = share(menu("오늘의 랜덤 메뉴", "가".repeat(200)));

		assertThat(page(id)).contains("<meta property=\"og:description\" content=\"" + "가".repeat(150) + "…\" />");
	}

	@Test
	void missingDescriptionFallsBackToTheDefault() throws Exception {
		String id = share(menu("오늘의 랜덤 메뉴", null));

		assertThat(page(id)).contains("<meta property=\"og:description\" content=\"오늘 먹을 메뉴를 골라 드려요\" />")
				.doesNotContain("null");
	}

	@Test
	void unknownLinkGetsTheDefaultCard() throws Exception {
		assertThat(page("Ab12Cd34Ef56Gh78Ij90Kl")).contains(
				"<title>오늘 뭐 먹지</title>",
				"<meta property=\"og:title\" content=\"오늘 뭐 먹지\" />",
				"<meta property=\"og:description\" content=\"오늘 먹을 메뉴를 골라 드려요\" />",
				"<div id=\"root\"></div>");
	}

	@Test
	void unreadableResultGetsTheDefaultCard() throws Exception {
		jdbc.sql("insert into saved_result (id, account_id, title, food_name, origin_name, payload, created_at) values (?, ?, ?, ?, ?, ?, ?)")
				.params("broken-payload-0000000", OWNER, "제목", "탄탄멘", null, "not json", Timestamp.from(Instant.now())).update();

		assertThat(page("broken-payload-0000000")).contains("<meta property=\"og:title\" content=\"오늘 뭐 먹지\" />");
	}
}
```

`ProdProfileTest`에 테스트 하나와 import 두 개를 추가한다:

```java
import static org.hamcrest.Matchers.allOf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
```

```java
	@Test
	void sharePreviewLinksUseTheAddressThePublicSees() throws Exception {
		mvc.perform(get("/s/Ab12Cd34Ef56Gh78Ij90Kl")
						.with(r -> { r.setServerName("pick.example"); r.setServerPort(80); return r; })
						.header("X-Forwarded-Proto", "https"))
				.andExpect(content().string(allOf(
						containsString("content=\"https://pick.example/og.png\""),
						containsString("content=\"https://pick.example/s/Ab12Cd34Ef56Gh78Ij90Kl\""))));
	}
```

`SpaControllerTest`에서 `sharedLinkOpenedDirectlyGetsTheFrontend` 테스트를 지운다(`/s/{id}`는 이제 `SharePageControllerTest`가 맡는다).

- [ ] **Step 3: 실패 확인**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew test --tests '*SharePageControllerTest' --tests '*ProdProfileTest' 2>&1 | tail -20`
Expected: `SharePageControllerTest` 7개와 `sharePreviewLinksUseTheAddressThePublicSees` 실패(`/s/{id}`가 아직 `forward:/index.html`이라 본문이 비어 있다). 나머지 `ProdProfileTest` 2개는 통과.

- [ ] **Step 4: 구현**

`src/main/java/com/example/pickyourfood/saved/SharePageController.java`:

```java
package com.example.pickyourfood.saved;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.json.JsonMapper;

// a share link gets the frontend's index.html with Open Graph tags, so messengers show the result as a card
@Controller
class SharePageController {

	private static final String SITE = "오늘 뭐 먹지";
	private static final String DEFAULT_DESCRIPTION = "오늘 먹을 메뉴를 골라 드려요";
	private static final int MAX_DESCRIPTION = 150;

	private final SavedRepository saved;
	private final JsonMapper json;

	// read on first use: the frontend build is only in the jar, not in a plain bootRun
	private String index;

	SharePageController(SavedRepository saved, JsonMapper json) {
		this.saved = saved;
		this.json = json;
	}

	@GetMapping(value = "/s/{id}", produces = "text/html;charset=UTF-8")
	@ResponseBody
	String page(@PathVariable String id) throws IOException {
		Card card = saved.find(id).map(stored -> card(stored.payload())).orElse(Card.DEFAULT);
		String tags = String.join("\n    ",
				meta("property", "og:type", "website"),
				meta("property", "og:site_name", SITE),
				meta("property", "og:title", card.title()),
				meta("property", "og:description", card.description()),
				meta("property", "og:image", ServletUriComponentsBuilder.fromCurrentContextPath().path("/og.png").toUriString()),
				meta("property", "og:url", ServletUriComponentsBuilder.fromCurrentRequestUri().toUriString()),
				meta("name", "twitter:card", "summary_large_image"));
		return index()
				.replaceFirst("<title>[^<]*</title>", Matcher.quoteReplacement("<title>" + escape(card.title()) + "</title>"))
				.replaceFirst("<head>", Matcher.quoteReplacement("<head>\n    " + tags));
	}

	// a payload that no longer reads as a SavedResult still opens the app, with the default card
	private Card card(String payload) {
		SavedResult result;
		try {
			result = json.readValue(payload, SavedResult.class);
		}
		catch (RuntimeException e) {
			return Card.DEFAULT;
		}
		List<String> parts = new ArrayList<>();
		if (result.best().description() != null && !result.best().description().isBlank()) parts.add(result.best().description());
		if (result.places() != null) parts.add(result.places().origin().name() + " 근처 맛집과 데이트 코스");
		String description = parts.isEmpty() ? DEFAULT_DESCRIPTION : String.join(" · ", parts);
		if (description.length() > MAX_DESCRIPTION) description = description.substring(0, MAX_DESCRIPTION) + "…";
		return new Card(result.best().name() + " · " + result.title(), description);
	}

	private String index() throws IOException {
		if (index == null) index = new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
		return index;
	}

	private static String meta(String attribute, String name, String content) {
		return "<meta " + attribute + "=\"" + name + "\" content=\"" + escape(content) + "\" />";
	}

	// the UTF-8 variant escapes only < > & " ', leaving · and … as they are
	private static String escape(String text) {
		return HtmlUtils.htmlEscape(text, "UTF-8");
	}

	private record Card(String title, String description) {
		static final Card DEFAULT = new Card(SITE, DEFAULT_DESCRIPTION);
	}
}
```

`web/SpaController.java`의 매핑을 `/saved`만 남긴다:

```java
	@GetMapping("/saved")
	String page() {
		return "forward:/index.html";
	}
```

- [ ] **Step 5: 통과 확인**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew test --tests '*SharePageControllerTest' --tests '*ProdProfileTest' --tests '*SpaControllerTest' 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: 전체 테스트**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL (기존 78개 − 1 + 8 = 85개, Docker가 꺼져 있으면 `PostgresMigrationTest`는 건너뜀).

- [ ] **Step 7: 커밋**

```bash
git add src/main/java/com/example/pickyourfood/saved/SharePageController.java \
  src/main/java/com/example/pickyourfood/web/SpaController.java \
  src/test/java/com/example/pickyourfood/saved/SharePageControllerTest.java \
  src/test/java/com/example/pickyourfood/web/SpaControllerTest.java \
  src/test/java/com/example/pickyourfood/account/ProdProfileTest.java \
  src/test/resources/static/index.html
git commit -m "feat: give share links an Open Graph card built from the saved result

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 카드 이미지 `og.png`

**Files:**
- Create: `frontend/public/og.png`

**Interfaces:**
- Consumes: Task 1의 `og:image` = `{origin}/og.png`.
- Produces: 운영에서 `/og.png` (Vite가 `public/`을 `dist/` 루트로 복사).

- [ ] **Step 1: 카드 HTML을 scratchpad에 만든다** (저장소에 넣지 않는다)

`<scratchpad>/og.html`:

```html
<!doctype html>
<html lang="ko">
<head>
<meta charset="UTF-8" />
<link rel="stylesheet" href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable-dynamic-subset.min.css" />
<style>
  html, body { margin: 0; width: 1200px; height: 630px; }
  body {
    font-family: 'Pretendard Variable', sans-serif;
    background: oklch(0.95 0.03 32);
    display: grid; grid-template-columns: 1fr 360px; align-items: center;
    padding: 0 96px; box-sizing: border-box; color: oklch(0.25 0.02 32);
  }
  h1 { font-size: 112px; line-height: 1; letter-spacing: -0.04em; margin: 0 0 32px; font-weight: 800; }
  p { font-size: 40px; margin: 0; color: oklch(0.45 0.04 32); font-weight: 500; }
  .dot { width: 320px; height: 320px; border-radius: 50%; background: oklch(0.6 0.15 32);
    display: grid; place-items: center; color: oklch(0.97 0.02 32); font-size: 150px; font-weight: 800; }
</style>
</head>
<body>
  <div><h1>오늘 뭐 먹지</h1><p>메뉴 뽑기 · 맛집 · 데이트 코스</p></div>
  <div class="dot">?</div>
</body>
</html>
```

- [ ] **Step 2: 캡처**

Run:
```bash
mkdir -p frontend/public
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --hide-scrollbars \
  --window-size=1200,630 --virtual-time-budget=5000 --screenshot=frontend/public/og.png "file://<scratchpad>/og.html"
file frontend/public/og.png; ls -l frontend/public/og.png
```
Expected: `PNG image data, 1200 x 630`, 300KB 이하.

- [ ] **Step 3: 눈으로 확인**

`frontend/public/og.png`를 열어 본다: 한글이 Pretendard로 나오고(네모 글자 없음), 잘린 곳이 없다.

- [ ] **Step 4: 커밋**

```bash
git add frontend/public/og.png
git commit -m "feat: add the share card image

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: README와 이미지로 확인

**Files:**
- Modify: `README.md` (배포 절차의 "무료 요금제는…" 문단 앞)

**Interfaces:**
- Consumes: Task 1의 `/s/{id}`, Task 2의 `/og.png`.

- [ ] **Step 1: README에 카카오 캐시 안내**

`README.md`의 5번 항목 바로 뒤, "무료 요금제는…" 문단 앞에 빈 줄을 두고 넣는다:

```markdown
공유 링크를 카카오톡에 붙이면 결과가 카드로 보인다. 카카오는 한 번 읽은 카드를 캐시하므로, 바뀐 카드를 바로 보려면 [카카오 개발자 도구](https://developers.kakao.com/tool/debugger/sharing)의 공유 디버거에서 그 주소의 캐시를 지운다.
```

- [ ] **Step 2: 이미지 빌드** (Docker Desktop 켜기)

Run: `docker build -t pick-your-food . > <scratchpad>/docker-build.log 2>&1; tail -3 <scratchpad>/docker-build.log`
Expected: 빌드 성공.

- [ ] **Step 3: Render처럼 실행**

```bash
docker network create pyf
docker run -d --name pyf-db --network pyf -e POSTGRES_PASSWORD=pw postgres:17-alpine
sleep 5
docker run -d --name pyf-app --network pyf -m 512m -p 10000:10000 -e PORT=10000 -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://pyf-db/postgres -e SPRING_DATASOURCE_USERNAME=postgres -e SPRING_DATASOURCE_PASSWORD=pw \
  -e KAKAO_REST_KEY=dummy pick-your-food
sleep 20
docker logs pyf-app 2>&1 | grep -E "Started|ERROR"
```
Expected: `Started PickYourFoodApplication`, `ERROR` 없음.

- [ ] **Step 4: 저장 결과를 하나 넣고 HTTP로 확인**

```bash
docker exec pyf-db psql -U postgres -c "insert into account (id, kakao_id, nickname, created_at) values (1, 'k1', '서윤', now());
insert into saved_result (id, account_id, title, food_name, origin_name, payload, created_at) values ('Ab12Cd34Ef56Gh78Ij90Kl', 1, '오늘의 랜덤 메뉴', '탄탄멘', null,
'{\"title\":\"오늘의 랜덤 메뉴\",\"best\":{\"id\":\"tantanmen\",\"name\":\"탄탄멘\",\"description\":\"고소하고 매콤한 국물\"},\"alternatives\":[]}', now());"
curl -s localhost:10000/s/Ab12Cd34Ef56Gh78Ij90Kl -H 'X-Forwarded-Proto: https' | head -15
for p in /s/Ab12Cd34Ef56Gh78Ij90Kl /s/xyz /og.png /saved /; do curl -s -o /dev/null -w "$p %{http_code} %{content_type}\n" localhost:10000$p; done
```
Expected:
- 첫 `curl`: `<head>` 바로 뒤에 `og:title` `탄탄멘 · 오늘의 랜덤 메뉴`, `og:image` `https://localhost:10000/og.png` 또는 `https://localhost/og.png`(포트는 프록시 헤더에 따라 다르다; `https://`로 시작하면 된다), `<title>탄탄멘 · 오늘의 랜덤 메뉴</title>`, 그리고 Vite가 만든 `<script type="module" … src="/assets/…">`가 남아 있다.
- `/s/…`, `/s/xyz`, `/saved`, `/` → `200 text/html`; `/og.png` → `200 image/png`.

- [ ] **Step 5: 앱이 뜨는지 스크린샷**

Run: `"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --window-size=430,900 --virtual-time-budget=8000 --screenshot=<scratchpad>/share-page.png http://localhost:10000/s/Ab12Cd34Ef56Gh78Ij90Kl`
Expected: 스크린샷에 저장된 결과 화면(탄탄멘)이 보인다.

- [ ] **Step 6: 정리**

```bash
docker rm -f pyf-app pyf-db; docker network rm pyf
```

- [ ] **Step 7: 커밋**

```bash
git add README.md
git commit -m "docs: note how to refresh Kakao's cached share card

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
