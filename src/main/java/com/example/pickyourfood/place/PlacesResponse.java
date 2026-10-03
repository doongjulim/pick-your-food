package com.example.pickyourfood.place;

import java.util.List;

public record PlacesResponse(Origin origin, List<Place> nearby, List<Place> famous, List<DateCourse> dateCourses) {

	public record Origin(String name, double lat, double lng) {
	}

	// rating, reviewCount, priceLevel and googleUrl are null when Google has no matching place
	public record Place(
			String id,
			String name,
			String address,
			int distanceMeters,
			double lat,
			double lng,
			String kakaoUrl,
			Double rating,
			Integer reviewCount,
			Integer priceLevel,
			List<Review> reviews,
			String googleUrl) {
	}

	public record Review(String author, Integer rating, String text, String when) {
	}

	public record Spot(String id, String name, String category, String address, double lat, double lng, String kakaoUrl) {
	}

	// walking distance and time between two stops: TMAP's route, with path its [lat, lng] points, or when that is
	// unavailable the straight line, with path null; walkMinutes rounds up to at least 1
	public record Leg(String from, String to, int meters, int walkMinutes, List<double[]> path) {
	}

	// cafe and sight are null when nothing is found near the restaurant; legs join only the stops that exist,
	// and routeUrl (a Kakao Map walking route through every stop) is null when the restaurant is the only stop
	public record DateCourse(Place restaurant, Spot cafe, Spot sight, List<Leg> legs, String routeUrl) {
	}
}
