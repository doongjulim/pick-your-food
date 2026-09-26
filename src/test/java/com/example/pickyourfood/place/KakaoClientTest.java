package com.example.pickyourfood.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.pickyourfood.place.KakaoClient.KakaoPlace;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class KakaoClientTest {

	private static final String ONE_PLACE = """
			{"documents":[{"id":"26338954","place_name":"탄탄면공방","category_name":"음식점 > 중식 > 중국요리",
			"address_name":"서울 성동구 성수동2가 1","road_address_name":"서울 성동구 연무장길 1","x":"127.0567","y":"37.5445",
			"distance":"820","place_url":"http://place.map.kakao.com/26338954","phone":""}],"meta":{"total_count":1}}""";

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final KakaoClient kakao = new KakaoClient(builder, "test-key");

	@Test
	void restaurantsSendsKeywordSearchAndParsesPlaces() {
		server.expect(requestTo(containsString("https://dapi.kakao.com/v2/local/search/keyword.json")))
				.andExpect(header("Authorization", "KakaoAK test-key"))
				.andExpect(queryParam("query", "ramen"))
				.andExpect(queryParam("category_group_code", "FD6"))
				.andExpect(queryParam("x", "127.05"))
				.andExpect(queryParam("y", "37.54"))
				.andExpect(queryParam("radius", "1000"))
				.andExpect(queryParam("sort", "distance"))
				.andExpect(queryParam("size", "5"))
				.andRespond(withSuccess(ONE_PLACE, MediaType.APPLICATION_JSON));

		List<KakaoPlace> places = kakao.restaurants("ramen", 37.54, 127.05, 1000, "distance", 5);

		assertThat(places).containsExactly(new KakaoPlace("26338954", "탄탄면공방", "중국요리", "서울 성동구 연무장길 1",
				37.5445, 127.0567, 820, "http://place.map.kakao.com/26338954"));
		server.verify();
	}

	@Test
	void koreanQueryIsPercentEncoded() {
		server.expect(requestTo(containsString("query=%ED%83%84%ED%83%84%EB%A9%98")))
				.andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));

		assertThat(kakao.restaurants("탄탄멘", 37.54, 127.05, 1000, "distance", 5)).isEmpty();
		server.verify();
	}

	@Test
	void nearestSendsCategorySearchSortedByDistance() {
		server.expect(requestTo(containsString("https://dapi.kakao.com/v2/local/search/category.json")))
				.andExpect(queryParam("category_group_code", "CE7"))
				.andExpect(queryParam("x", "127.05"))
				.andExpect(queryParam("y", "37.54"))
				.andExpect(queryParam("radius", "500"))
				.andExpect(queryParam("sort", "distance"))
				.andExpect(queryParam("size", "1"))
				.andRespond(withSuccess(ONE_PLACE, MediaType.APPLICATION_JSON));

		assertThat(kakao.nearest(KakaoClient.CAFE, 37.54, 127.05, 500)).map(KakaoPlace::id).hasValue("26338954");
		server.verify();
	}

	@Test
	void fallsBackToLotAddressAndZeroDistance() {
		server.expect(requestTo(containsString("/v2/local/search/keyword.json")))
				.andExpect(queryParam("query", "seongsu"))
				.andExpect(queryParam("size", "1"))
				.andRespond(withSuccess("""
						{"documents":[{"id":"1","place_name":"성수동","category_name":"지역","address_name":"서울 성동구 성수동",
						"road_address_name":"","x":"127.0557","y":"37.5445","distance":"","place_url":"http://place.map.kakao.com/1"}]}""",
						MediaType.APPLICATION_JSON));

		assertThat(kakao.locate("seongsu")).hasValueSatisfying(place -> {
			assertThat(place.address()).isEqualTo("서울 성동구 성수동");
			assertThat(place.distanceMeters()).isZero();
			assertThat(place.lat()).isEqualTo(37.5445);
			assertThat(place.lng()).isEqualTo(127.0557);
		});
	}

	@Test
	void noDocumentsIsEmpty() {
		server.expect(requestTo(containsString("/v2/local/search/keyword.json")))
				.andRespond(withSuccess("{\"documents\":[]}", MediaType.APPLICATION_JSON));

		assertThat(kakao.locate("nowhere")).isEmpty();
	}

	@Test
	void kakaoFailureIsBadGateway() {
		server.expect(requestTo(containsString("/v2/local/search/keyword.json"))).andRespond(withServerError());

		assertThatThrownBy(() -> kakao.locate("seongsu")).isInstanceOfSatisfying(ResponseStatusException.class,
				e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
	}

	@Test
	void missingKeyIsBadGatewayWithoutCallingKakao() {
		KakaoClient noKey = new KakaoClient(builder, "");

		assertThatThrownBy(() -> noKey.locate("seongsu")).isInstanceOfSatisfying(ResponseStatusException.class,
				e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
		server.verify();
	}
}
