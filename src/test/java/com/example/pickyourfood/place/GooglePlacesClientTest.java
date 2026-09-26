package com.example.pickyourfood.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.pickyourfood.place.GooglePlacesClient.GoogleInfo;
import com.example.pickyourfood.place.PlacesResponse.Review;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class GooglePlacesClientTest {

	private static final String URL = "https://places.googleapis.com/v1/places:searchText";

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final GooglePlacesClient google = new GooglePlacesClient(builder, "g-key");

	@Test
	void sendsTextSearchAndParsesPlace() {
		server.expect(requestTo(URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("X-Goog-Api-Key", "g-key"))
				.andExpect(header("X-Goog-FieldMask", GooglePlacesClient.FIELDS))
				.andExpect(jsonPath("$.textQuery").value("탄탄면공방 서울 성동구 연무장길 1"))
				.andExpect(jsonPath("$.languageCode").value("ko"))
				.andExpect(jsonPath("$.pageSize").value(1))
				.andExpect(jsonPath("$.locationBias.circle.center.latitude").value(37.5445))
				.andExpect(jsonPath("$.locationBias.circle.center.longitude").value(127.0567))
				.andExpect(jsonPath("$.locationBias.circle.radius").value(200.0))
				.andRespond(withSuccess("""
						{"places":[{"location":{"latitude":37.5446,"longitude":127.0568},"rating":4.3,"userRatingCount":1284,
						"priceLevel":"PRICE_LEVEL_MODERATE","googleMapsUri":"https://maps.google.com/?cid=1",
						"reviews":[
						{"rating":5,"text":{"text":"국물이 진해요","languageCode":"ko"},"relativePublishTimeDescription":"2주 전","authorAttribution":{"displayName":"김서윤"}},
						{"rating":4,"text":{"text":"면이 쫄깃해요"},"relativePublishTimeDescription":"1달 전","authorAttribution":{"displayName":"박도현"}},
						{"rating":4,"originalText":{"text":"only original text"}},
						{"rating":3,"text":{"text":"웨이팅이 길어요"},"relativePublishTimeDescription":"3달 전"},
						{"rating":2,"text":{"text":"다섯 번째"},"relativePublishTimeDescription":"1년 전","authorAttribution":{"displayName":"이하준"}}]}]}""",
						MediaType.APPLICATION_JSON));

		GoogleInfo info = google.find("탄탄면공방 서울 성동구 연무장길 1", 37.5445, 127.0567).orElseThrow();

		assertThat(info.lat()).isEqualTo(37.5446);
		assertThat(info.lng()).isEqualTo(127.0568);
		assertThat(info.rating()).isEqualTo(4.3);
		assertThat(info.reviewCount()).isEqualTo(1284);
		assertThat(info.priceLevel()).isEqualTo(2);
		assertThat(info.url()).isEqualTo("https://maps.google.com/?cid=1");
		// the review without "text" is skipped, and at most 3 are kept
		assertThat(info.reviews()).containsExactly(
				new Review("김서윤", 5, "국물이 진해요", "2주 전"),
				new Review("박도현", 4, "면이 쫄깃해요", "1달 전"),
				new Review(null, 3, "웨이팅이 길어요", "3달 전"));
		server.verify();
	}

	@Test
	void placeWithoutOptionalFieldsHasNulls() {
		server.expect(requestTo(URL)).andRespond(withSuccess("""
				{"places":[{"location":{"latitude":37.5,"longitude":127.0}}]}""", MediaType.APPLICATION_JSON));

		GoogleInfo info = google.find("q", 37.5, 127.0).orElseThrow();

		assertThat(info.rating()).isNull();
		assertThat(info.reviewCount()).isNull();
		assertThat(info.priceLevel()).isNull();
		assertThat(info.reviews()).isEmpty();
		assertThat(info.url()).isNull();
	}

	@Test
	void noResultIsEmpty() {
		server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		assertThat(google.find("q", 37.5, 127.0)).isEmpty();
	}

	@Test
	void httpErrorPropagates() {
		server.expect(requestTo(URL)).andRespond(withServerError());

		assertThatThrownBy(() -> google.find("q", 37.5, 127.0)).isInstanceOf(RestClientException.class);
	}

	@Test
	void missingKeyIsEmptyWithoutCallingGoogle() {
		assertThat(new GooglePlacesClient(builder, "").find("q", 37.5, 127.0)).isEmpty();
		server.verify();
	}

	@Test
	void priceLevelsMapToOneThroughFour() {
		assertThat(GooglePlacesClient.toLevel("PRICE_LEVEL_INEXPENSIVE")).isEqualTo(1);
		assertThat(GooglePlacesClient.toLevel("PRICE_LEVEL_MODERATE")).isEqualTo(2);
		assertThat(GooglePlacesClient.toLevel("PRICE_LEVEL_EXPENSIVE")).isEqualTo(3);
		assertThat(GooglePlacesClient.toLevel("PRICE_LEVEL_VERY_EXPENSIVE")).isEqualTo(4);
		assertThat(GooglePlacesClient.toLevel("PRICE_LEVEL_FREE")).isNull();
		assertThat(GooglePlacesClient.toLevel("PRICE_LEVEL_UNSPECIFIED")).isNull();
		assertThat(GooglePlacesClient.toLevel(null)).isNull();
	}
}
