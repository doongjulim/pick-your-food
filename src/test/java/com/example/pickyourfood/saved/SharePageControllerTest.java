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

	@Test
	void wrongShapeResultGetsTheDefaultCard() throws Exception {
		jdbc.sql("insert into saved_result (id, account_id, title, food_name, origin_name, payload, created_at) values (?, ?, ?, ?, ?, ?, ?)")
				.params("wrong-shape-0000000000", OWNER, "제목", "탄탄멘", null, "{}", Timestamp.from(Instant.now())).update();

		assertThat(page("wrong-shape-0000000000")).contains("<meta property=\"og:title\" content=\"오늘 뭐 먹지\" />");
	}
}
