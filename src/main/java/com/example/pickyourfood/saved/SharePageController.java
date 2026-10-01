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
