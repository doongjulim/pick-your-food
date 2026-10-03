package com.example.pickyourfood.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.pickyourfood.place.TmapClient.Route;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class TmapClientTest {

	private static final String URL = "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1";

	// trimmed from a real answer: totals on the first feature, two lines that share a joint point, a turn point between
	private static final String ROUTE = """
			{"type":"FeatureCollection","features":[
			{"type":"Feature","geometry":{"type":"Point","coordinates":[127.05,37.56]},"properties":{"totalDistance":480,"totalTime":372,"pointType":"SP"}},
			{"type":"Feature","geometry":{"type":"LineString","coordinates":[[127.05,37.56],[127.051,37.561]]},"properties":{"distance":200}},
			{"type":"Feature","geometry":{"type":"Point","coordinates":[127.051,37.561]},"properties":{"pointType":"GP"}},
			{"type":"Feature","geometry":{"type":"LineString","coordinates":[[127.051,37.561],[127.052,37.564]]},"properties":{"distance":280}}]}""";

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final TmapClient tmap = new TmapClient(builder, "test-key");

	@Test
	void walkingRouteJoinsTheLinesAsLatLng() {
		server.expect(requestTo(URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("appKey", "test-key"))
				.andExpect(jsonPath("$.startX").value(127.05))
				.andExpect(jsonPath("$.startY").value(37.56))
				.andExpect(jsonPath("$.endX").value(127.052))
				.andExpect(jsonPath("$.endY").value(37.564))
				.andExpect(jsonPath("$.reqCoordType").value("WGS84GEO"))
				.andExpect(jsonPath("$.resCoordType").value("WGS84GEO"))
				.andRespond(withSuccess(ROUTE, MediaType.APPLICATION_JSON));

		Route route = tmap.route(37.56, 127.05, 37.564, 127.052).orElseThrow();

		assertThat(route.meters()).isEqualTo(480);
		assertThat(route.seconds()).isEqualTo(372);
		assertThat(route.path()).containsExactly(
				new double[] { 37.56, 127.05 }, new double[] { 37.561, 127.051 }, new double[] { 37.564, 127.052 });
		server.verify();
	}

	@Test
	void unusableResponsesThrow() {
		String noFeatures = "{\"type\":\"FeatureCollection\",\"features\":[]}";
		String noTotals = "{\"features\":[{\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[127.05,37.56],[127.051,37.561]]},\"properties\":{}}]}";
		String badPoint = "{\"features\":[{\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[127.05],[127.051,37.561]]},"
				+ "\"properties\":{\"totalDistance\":1,\"totalTime\":1}}]}";
		String noLine = "{\"features\":[{\"geometry\":{\"type\":\"Point\",\"coordinates\":[127.05,37.56]},"
				+ "\"properties\":{\"totalDistance\":0,\"totalTime\":0}}]}";
		for (String body : new String[] { noFeatures, noTotals, badPoint, noLine }) {
			server.reset();
			server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
			assertThatThrownBy(() -> tmap.route(37.56, 127.05, 37.564, 127.052)).as(body).isInstanceOf(RestClientException.class);
		}
	}

	@Test
	void serverErrorThrows() {
		server.expect(requestTo(URL)).andRespond(withServerError());

		assertThatThrownBy(() -> tmap.route(37.56, 127.05, 37.564, 127.052)).isInstanceOf(RestClientException.class);
	}

	@Test
	void withoutAKeyNothingIsSent() {
		RestClient.Builder unused = RestClient.builder();
		MockRestServiceServer silent = MockRestServiceServer.bindTo(unused).build();

		assertThat(new TmapClient(unused, "").route(37.56, 127.05, 37.564, 127.052)).isEmpty();
		silent.verify();
	}
}
