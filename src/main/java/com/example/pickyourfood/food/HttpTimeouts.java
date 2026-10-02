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
