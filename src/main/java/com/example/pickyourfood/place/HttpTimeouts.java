package com.example.pickyourfood.place;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

final class HttpTimeouts {

	private HttpTimeouts() {
	}

	static SimpleClientHttpRequestFactory factory() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(2));
		factory.setReadTimeout(Duration.ofSeconds(3));
		return factory;
	}
}
