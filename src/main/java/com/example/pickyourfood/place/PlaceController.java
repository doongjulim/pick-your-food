package com.example.pickyourfood.place;

import com.example.pickyourfood.place.PlacesResponse.Course;
import com.example.pickyourfood.place.PlacesResponse.Origin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class PlaceController {

	private final PlaceService places;
	private final String mapKey;

	PlaceController(PlaceService places, @Value("${kakao.js-key:}") String mapKey) {
		this.places = places;
		this.mapKey = mapKey;
	}

	@GetMapping("/api/places")
	PlacesResponse places(
			@RequestParam String food,
			@RequestParam(required = false) Double lat,
			@RequestParam(required = false) Double lng,
			@RequestParam(required = false) String near) {
		if (food.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "food is required");
		Origin origin;
		if (near != null && !near.isBlank()) origin = places.locate(near.strip());
		else if (lat != null && lng != null) origin = new Origin("현재 위치", lat, lng);
		else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lat and lng, or near, is required");
		return places.search(food.strip(), origin);
	}

	// the client sends the restaurant it already has: Kakao has no lookup by id
	@GetMapping("/api/places/course")
	Course course(@RequestParam String id, @RequestParam String name, @RequestParam double lat, @RequestParam double lng) {
		// written as "not within" so NaN, which fails every comparison, is rejected too
		if (!fits(id) || !fits(name) || !(Math.abs(lat) <= 90) || !(Math.abs(lng) <= 180)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id, name, lat or lng is invalid");
		}
		return places.course(id.strip(), name.strip(), lat, lng);
	}

	private static boolean fits(String value) {
		return !value.isBlank() && value.strip().length() <= 100;
	}

	// the Kakao Maps JavaScript key is public by design (Kakao only honours it on our domains); null hides the map
	@GetMapping("/api/places/map-key")
	MapKey mapKey() {
		return new MapKey(mapKey.isBlank() ? null : mapKey);
	}

	record MapKey(String key) {
	}
}
