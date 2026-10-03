package com.example.pickyourfood.place;

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

	// the Kakao Maps JavaScript key is public by design (Kakao only honours it on our domains); null hides the map
	@GetMapping("/api/places/map-key")
	MapKey mapKey() {
		return new MapKey(mapKey.isBlank() ? null : mapKey);
	}

	record MapKey(String key) {
	}
}
