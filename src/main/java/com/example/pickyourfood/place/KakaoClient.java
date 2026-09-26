package com.example.pickyourfood.place;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriBuilder;

@Component
public class KakaoClient {

	static final String RESTAURANT = "FD6";
	static final String CAFE = "CE7";
	static final String SIGHT = "AT4";

	private final RestClient http;
	private final String key;

	@Autowired
	public KakaoClient(@Value("${kakao.rest-key:}") String key) {
		this(RestClient.builder().requestFactory(HttpTimeouts.factory()), key);
	}

	KakaoClient(RestClient.Builder builder, String key) {
		this.http = builder.baseUrl("https://dapi.kakao.com").build();
		this.key = key;
	}

	public List<KakaoPlace> restaurants(String query, double lat, double lng, int radius, String sort, int size) {
		return search(uri -> uri.path("/v2/local/search/keyword.json")
				.queryParam("query", "{query}")
				.queryParam("category_group_code", RESTAURANT)
				.queryParam("x", lng)
				.queryParam("y", lat)
				.queryParam("radius", radius)
				.queryParam("sort", sort)
				.queryParam("size", size)
				.build(query));
	}

	public Optional<KakaoPlace> nearest(String category, double lat, double lng, int radius) {
		return search(uri -> uri.path("/v2/local/search/category.json")
				.queryParam("category_group_code", category)
				.queryParam("x", lng)
				.queryParam("y", lat)
				.queryParam("radius", radius)
				.queryParam("sort", "distance")
				.queryParam("size", 1)
				.build()).stream().findFirst();
	}

	// turns a typed place such as "성수동" into coordinates
	public Optional<KakaoPlace> locate(String query) {
		return search(uri -> uri.path("/v2/local/search/keyword.json")
				.queryParam("query", "{query}")
				.queryParam("size", 1)
				.build(query)).stream().findFirst();
	}

	// typed text goes in as a URI variable so "{", "+" and "&" are encoded instead of parsed
	private List<KakaoPlace> search(Function<UriBuilder, URI> uri) {
		if (key.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "KAKAO_REST_KEY is not set");
		try {
			Response response = http.get().uri(uri).header("Authorization", "KakaoAK " + key).retrieve().body(Response.class);
			if (response == null || response.documents() == null) return List.of();
			return response.documents().stream().map(Document::toPlace).toList();
		} catch (RestClientException e) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Kakao request failed", e);
		}
	}

	public record KakaoPlace(String id, String name, String category, String address, double lat, double lng, int distanceMeters, String url) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Response(List<Document> documents) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Document(
			String id,
			@JsonProperty("place_name") String placeName,
			@JsonProperty("category_name") String categoryName,
			@JsonProperty("address_name") String addressName,
			@JsonProperty("road_address_name") String roadAddressName,
			String x,
			String y,
			String distance,
			@JsonProperty("place_url") String placeUrl) {

		KakaoPlace toPlace() {
			// "음식점 > 중식 > 중국요리" -> "중국요리"
			String category = categoryName == null ? "" : categoryName.substring(categoryName.lastIndexOf('>') + 1).strip();
			String address = roadAddressName == null || roadAddressName.isBlank() ? addressName : roadAddressName;
			// distance is empty when the search had no x/y
			int meters = distance == null || distance.isBlank() ? 0 : Integer.parseInt(distance);
			return new KakaoPlace(id, placeName, category, address, Double.parseDouble(y), Double.parseDouble(x), meters, placeUrl);
		}
	}
}
