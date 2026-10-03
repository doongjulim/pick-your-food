package com.example.pickyourfood.saved;

import java.util.List;

// what a saved link shows; Google fields (rating, reviews, …) have no component here, so they are dropped on save
record SavedResult(String title, Food best, List<Food> alternatives, Places places) {

	record Food(String id, String name, String description) {
	}

	record Places(Origin origin, List<Place> nearby, List<Place> famous, List<DateCourse> dateCourses) {
	}

	record Origin(String name, double lat, double lng) {
	}

	record Place(String id, String name, String address, int distanceMeters, double lat, double lng, String kakaoUrl) {
	}

	record Spot(String id, String name, String category, String address, double lat, double lng, String kakaoUrl) {
	}

	// path is the walking route as [lat, lng] points; null for straight-line legs and results saved before routes
	record Leg(String from, String to, int meters, int walkMinutes, List<double[]> path) {
	}

	record DateCourse(Place restaurant, Spot cafe, Spot sight, List<Leg> legs, String routeUrl) {
	}
}
