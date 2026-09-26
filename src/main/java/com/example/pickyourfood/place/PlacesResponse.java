package com.example.pickyourfood.place;

import java.util.List;

public record PlacesResponse(Origin origin, List<Place> nearby, List<Place> famous, DateCourse dateCourse) {

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

	public record Spot(String name, String category, String address, int distanceMeters, String kakaoUrl) {
	}

	// cafe and sight are null when nothing is found near the restaurant
	public record DateCourse(Place restaurant, Spot cafe, Spot sight) {
	}
}
