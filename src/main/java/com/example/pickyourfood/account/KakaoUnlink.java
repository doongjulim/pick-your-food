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
