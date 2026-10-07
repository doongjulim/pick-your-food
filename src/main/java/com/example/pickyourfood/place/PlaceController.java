package com.example.pickyourfood.place;

import com.example.pickyourfood.place.PlaceService.Stop;
import com.example.pickyourfood.place.PlacesResponse.Course;
import com.example.pickyourfood.place.PlacesResponse.Origin;
import com.example.pickyourfood.place.PlacesResponse.Walk;
import java.util.stream.IntStream;
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
		if (!fits(id) || !fits(name) || !within(lat, lng)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id, name, lat or lng is invalid");
		}
		return places.course(id.strip(), name.strip(), lat, lng);
	}

	// the stops on screen in order (the restaurant, then the cafe and sight that exist), each parameter repeated per stop;
	// arrays, not lists: a repeated parameter is never split at commas, so a name like "어니언, 성수" stays whole
	@GetMapping("/api/places/legs")
	Walk legs(@RequestParam String[] id, @RequestParam String[] name, @RequestParam double[] lat, @RequestParam double[] lng) {
		int size = id.length;
		boolean valid = size >= 2 && size <= 3 && name.length == size && lat.length == size && lng.length == size
				&& IntStream.range(0, size).allMatch(i -> fits(id[i]) && fits(name[i]) && within(lat[i], lng[i]));
		if (!valid) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "2 or 3 stops with a valid id, name, lat and lng are required");
		return places.walk(IntStream.range(0, size).mapToObj(i -> new Stop(id[i].strip(), name[i].strip(), lat[i], lng[i])).toList());
	}

	private static boolean fits(String value) {
		return !value.isBlank() && value.strip().length() <= 100 && value.chars().noneMatch(Character::isISOControl);
	}

	// written as "within" so NaN, which fails every comparison, is rejected too
	private static boolean within(double lat, double lng) {
		return Math.abs(lat) <= 90 && Math.abs(lng) <= 180;
	}

	// the Kakao Maps JavaScript key is public by design (Kakao only honours it on our domains); null hides the map
	@GetMapping("/api/places/map-key")
	MapKey mapKey() {
		return new MapKey(mapKey.isBlank() ? null : mapKey);
	}

	record MapKey(String key) {
	}
}
