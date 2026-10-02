package com.example.pickyourfood.place;

import com.example.pickyourfood.food.HttpTimeouts;
import com.example.pickyourfood.place.PlacesResponse.Review;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class GooglePlacesClient {

	static final String FIELDS =
			"places.location,places.rating,places.userRatingCount,places.priceLevel,places.reviews,places.googleMapsUri";

	private final RestClient http;
	private final String key;

	@Autowired
	public GooglePlacesClient(@Value("${google.places-key:}") String key) {
		this(RestClient.builder().requestFactory(HttpTimeouts.factory()), key);
	}

	GooglePlacesClient(RestClient.Builder builder, String key) {
		this.http = builder.baseUrl("https://places.googleapis.com").build();
		this.key = key;
	}

	// empty without a key or a result; HTTP errors propagate as RestClientException
	public Optional<GoogleInfo> find(String text, double lat, double lng) {
		if (key.isBlank()) return Optional.empty();
		Map<String, Object> body = Map.of(
				"textQuery", text,
				"languageCode", "ko",
				"pageSize", 1,
				"locationBias", Map.of("circle", Map.of(
						"center", Map.of("latitude", lat, "longitude", lng),
						"radius", 200.0)));
		Response response = http.post().uri("/v1/places:searchText")
				.header("X-Goog-Api-Key", key)
				.header("X-Goog-FieldMask", FIELDS)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(Response.class);
		if (response == null || response.places() == null) return Optional.empty();
		return response.places().stream().filter(place -> place.location() != null).findFirst().map(GooglePlace::toInfo);
	}

	static Integer toLevel(String priceLevel) {
		if (priceLevel == null) return null;
		return switch (priceLevel) {
			case "PRICE_LEVEL_INEXPENSIVE" -> 1;
			case "PRICE_LEVEL_MODERATE" -> 2;
			case "PRICE_LEVEL_EXPENSIVE" -> 3;
			case "PRICE_LEVEL_VERY_EXPENSIVE" -> 4;
			default -> null;
		};
	}

	public record GoogleInfo(double lat, double lng, Double rating, Integer reviewCount, Integer priceLevel, List<Review> reviews, String url) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Response(List<GooglePlace> places) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record GooglePlace(LatLng location, Double rating, Integer userRatingCount, String priceLevel, List<GoogleReview> reviews, String googleMapsUri) {

		GoogleInfo toInfo() {
			List<Review> kept = reviews == null ? List.of() : reviews.stream()
					.filter(review -> review.text() != null && review.text().text() != null)
					.limit(3)
					.map(review -> new Review(
							review.authorAttribution() == null ? null : review.authorAttribution().displayName(),
							review.rating(),
							review.text().text(),
							review.relativePublishTimeDescription()))
					.toList();
			return new GoogleInfo(location.latitude(), location.longitude(), rating, userRatingCount, toLevel(priceLevel), kept, googleMapsUri);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record LatLng(double latitude, double longitude) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record GoogleReview(Integer rating, Text text, String relativePublishTimeDescription, Author authorAttribution) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Text(String text) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Author(String displayName) {
	}
}
