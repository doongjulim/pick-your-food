package com.example.pickyourfood.place;

import com.example.pickyourfood.food.HttpTimeouts;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

// TMAP pedestrian routes (SK open API)
@Component
public class TmapClient {

	private final RestClient http;
	private final String key;

	@Autowired
	public TmapClient(@Value("${tmap.app-key:}") String key) {
		this(RestClient.builder().requestFactory(HttpTimeouts.factory()), key);
	}

	TmapClient(RestClient.Builder builder, String key) {
		this.http = builder.baseUrl("https://apis.openapi.sk.com").build();
		this.key = key;
	}

	// empty without a key; RestClientException when TMAP fails or answers something unusable
	public Optional<Route> route(double fromLat, double fromLng, double toLat, double toLng) {
		if (key.isBlank()) return Optional.empty();
		Response response = http.post()
				.uri("/tmap/routes/pedestrian?version=1")
				.header("appKey", key)
				.contentType(MediaType.APPLICATION_JSON)
				// the names are required labels only; ASCII avoids TMAP's URL-encoding rule for them
				.body(Map.of("startX", fromLng, "startY", fromLat, "endX", toLng, "endY", toLat,
						"startName", "start", "endName", "end",
						"reqCoordType", "WGS84GEO", "resCoordType", "WGS84GEO"))
				.retrieve()
				.body(Response.class);
		return Optional.of(parse(response));
	}

	// totals sit on the first feature; the LineStrings, in order, are the path as [lng, lat]
	private static Route parse(Response response) {
		if (response == null || response.features() == null || response.features().isEmpty()) {
			throw new RestClientException("TMAP: no route");
		}
		Properties totals = response.features().get(0).properties();
		if (totals == null || totals.totalDistance() == null || totals.totalTime() == null) {
			throw new RestClientException("TMAP: no totals");
		}
		List<double[]> path = new ArrayList<>();
		for (Feature feature : response.features()) {
			if (feature.geometry() == null || !"LineString".equals(feature.geometry().type())) continue;
			for (JsonNode point : feature.geometry().coordinates()) {
				if (point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) {
					throw new RestClientException("TMAP: bad point " + point);
				}
				double[] latLng = { point.get(1).asDouble(), point.get(0).asDouble() };
				// consecutive lines share their joint point
				if (path.isEmpty() || !Arrays.equals(path.get(path.size() - 1), latLng)) path.add(latLng);
			}
		}
		if (path.size() < 2) throw new RestClientException("TMAP: no line");
		return new Route(totals.totalDistance(), totals.totalTime(), path);
	}

	public record Route(int meters, int seconds, List<double[]> path) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Response(List<Feature> features) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Feature(Geometry geometry, Properties properties) {
	}

	// a Point's coordinates are one pair, a LineString's a list of pairs
	@JsonIgnoreProperties(ignoreUnknown = true)
	record Geometry(String type, JsonNode coordinates) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Properties(Integer totalDistance, Integer totalTime) {
	}
}
