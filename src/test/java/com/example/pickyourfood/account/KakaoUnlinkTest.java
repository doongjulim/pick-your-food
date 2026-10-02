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
