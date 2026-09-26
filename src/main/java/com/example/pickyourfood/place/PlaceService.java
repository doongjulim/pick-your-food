package com.example.pickyourfood.place;

import com.example.pickyourfood.place.GooglePlacesClient.GoogleInfo;
import com.example.pickyourfood.place.KakaoClient.KakaoPlace;
import com.example.pickyourfood.place.PlacesResponse.DateCourse;
import com.example.pickyourfood.place.PlacesResponse.Leg;
import com.example.pickyourfood.place.PlacesResponse.Origin;
import com.example.pickyourfood.place.PlacesResponse.Place;
import com.example.pickyourfood.place.PlacesResponse.Spot;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlaceService {

	static final int NEARBY_RADIUS = 1_000;
	static final int FAMOUS_RADIUS = 20_000;
	static final int LIST_SIZE = 5;
	// Kakao's maximum page; the extra candidates replace ones already in the nearby list
	static final int FAMOUS_CANDIDATES = 15;
	static final int MATCH_METERS = 200;
	static final int CAFE_RADIUS = 500;
	static final int SIGHT_RADIUS = 1_000;
	static final int COURSES = 3;
	// about 4km/h
	static final int WALK_METERS_PER_MINUTE = 67;
	static final String ROUTE_BASE = "https://map.kakao.com/link/by/walk/";
	static final Duration CACHE_TTL = Duration.ofHours(24);

	// most reviews first, then best rating; places Google does not know go last
	static final Comparator<Place> FAMOUS_ORDER = Comparator
			.comparing(Place::reviewCount, Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(Place::rating, Comparator.nullsLast(Comparator.reverseOrder()));

	private static final Logger log = LoggerFactory.getLogger(PlaceService.class);

	private final KakaoClient kakao;
	private final GooglePlacesClient google;
	// ponytail: unbounded map, entries only expire when read again; swap for Caffeine if memory grows
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();
	private final ExecutorService pool = Executors.newFixedThreadPool(10, task -> {
		Thread thread = new Thread(task, "place-lookups");
		thread.setDaemon(true);
		return thread;
	});

	PlaceService(KakaoClient kakao, GooglePlacesClient google) {
		this.kakao = kakao;
		this.google = google;
	}

	public Origin locate(String near) {
		KakaoPlace place = kakao.locate(near)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no place called " + near));
		return new Origin(near, place.lat(), place.lng());
	}

	public PlacesResponse search(String food, Origin origin) {
		List<KakaoPlace> near = kakao.restaurants(food, origin.lat(), origin.lng(), NEARBY_RADIUS, "distance", LIST_SIZE);
		Set<String> nearIds = near.stream().map(KakaoPlace::id).collect(Collectors.toSet());
		List<KakaoPlace> far = kakao.restaurants(food, origin.lat(), origin.lng(), FAMOUS_RADIUS, "accuracy", FAMOUS_CANDIDATES).stream()
				.filter(place -> !nearIds.contains(place.id()))
				.limit(LIST_SIZE)
				.toList();

		// start every Google lookup before waiting on any of them
		List<CompletableFuture<Place>> nearbyLookups = near.stream().map(this::enrichAsync).toList();
		List<CompletableFuture<Place>> famousLookups = far.stream().map(this::enrichAsync).toList();
		List<Place> nearby = nearbyLookups.stream().map(CompletableFuture::join).toList();
		List<Place> famous = famousLookups.stream().map(CompletableFuture::join).sorted(FAMOUS_ORDER).toList();

		return new PlacesResponse(origin, nearby, famous, dateCourses(famous));
	}

	private CompletableFuture<Place> enrichAsync(KakaoPlace place) {
		return CompletableFuture.supplyAsync(() -> toPlace(place, googleInfo(place)), pool);
	}

	private Optional<GoogleInfo> googleInfo(KakaoPlace place) {
		Cached cached = cache.get(place.id());
		if (cached != null && cached.at().plus(CACHE_TTL).isAfter(Instant.now())) return cached.info();
		try {
			Optional<GoogleInfo> info = google.find(place.name() + " " + place.address(), place.lat(), place.lng())
					.filter(found -> meters(place.lat(), place.lng(), found.lat(), found.lng()) <= MATCH_METERS);
			cache.put(place.id(), new Cached(info, Instant.now()));
			return info;
		} catch (RuntimeException e) {
			// not cached, so the next search asks Google again
			log.warn("Google lookup failed for {}: {}", place.name(), e.getMessage());
			return Optional.empty();
		}
	}

	private static Place toPlace(KakaoPlace place, Optional<GoogleInfo> info) {
		return new Place(place.id(), place.name(), place.address(), place.distanceMeters(), place.lat(), place.lng(), place.url(),
				info.map(GoogleInfo::rating).orElse(null),
				info.map(GoogleInfo::reviewCount).orElse(null),
				info.map(GoogleInfo::priceLevel).orElse(null),
				info.map(GoogleInfo::reviews).orElse(List.of()),
				info.map(GoogleInfo::url).orElse(null));
	}

	private List<DateCourse> dateCourses(List<Place> famous) {
		List<Place> restaurants = famous.stream().limit(COURSES).toList();
		// start every Kakao lookup before waiting on any of them
		List<CompletableFuture<List<KakaoPlace>>> cafes = restaurants.stream()
				.map(restaurant -> candidatesAsync(KakaoClient.CAFE, restaurant, CAFE_RADIUS)).toList();
		List<CompletableFuture<List<KakaoPlace>>> sights = restaurants.stream()
				.map(restaurant -> candidatesAsync(KakaoClient.SIGHT, restaurant, SIGHT_RADIUS)).toList();
		// picked in course order, so an earlier course keeps its nearest spot
		Set<String> used = new HashSet<>();
		List<DateCourse> courses = new ArrayList<>();
		for (int i = 0; i < restaurants.size(); i++) {
			Spot cafe = pick(cafes.get(i).join(), used);
			Spot sight = pick(sights.get(i).join(), used);
			courses.add(course(restaurants.get(i), cafe, sight));
		}
		return courses;
	}

	private CompletableFuture<List<KakaoPlace>> candidatesAsync(String category, Place from, int radius) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return kakao.nearby(category, from.lat(), from.lng(), radius);
			} catch (RuntimeException e) {
				// the course just leaves this step empty
				log.warn("Kakao {} search failed near {}: {}", category, from.name(), e.getMessage());
				return List.of();
			}
		}, pool);
	}

	// nearest candidate no earlier course uses; when every one is taken, the nearest anyway
	private static Spot pick(List<KakaoPlace> candidates, Set<String> used) {
		return candidates.stream().filter(found -> !used.contains(found.id())).findFirst()
				.or(() -> candidates.stream().findFirst())
				.map(found -> {
					used.add(found.id());
					return new Spot(found.id(), found.name(), found.category(), found.address(), found.lat(), found.lng(), found.url());
				})
				.orElse(null);
	}

	private static DateCourse course(Place restaurant, Spot cafe, Spot sight) {
		List<Stop> stops = new ArrayList<>();
		stops.add(new Stop(restaurant.name(), restaurant.lat(), restaurant.lng()));
		if (cafe != null) stops.add(new Stop(cafe.name(), cafe.lat(), cafe.lng()));
		if (sight != null) stops.add(new Stop(sight.name(), sight.lat(), sight.lng()));
		List<Leg> legs = IntStream.range(1, stops.size()).mapToObj(i -> leg(stops.get(i - 1), stops.get(i))).toList();
		String routeUrl = legs.isEmpty() ? null
				: ROUTE_BASE + stops.stream()
						.map(stop -> routeName(stop.name()) + "," + stop.lat() + "," + stop.lng())
						.collect(Collectors.joining("/"));
		return new DateCourse(restaurant, cafe, sight, legs, routeUrl);
	}

	private static Leg leg(Stop from, Stop to) {
		int meters = meters(from.lat(), from.lng(), to.lat(), to.lng());
		int minutes = Math.max(1, (int) Math.ceil(meters / (double) WALK_METERS_PER_MINUTE));
		return new Leg(from.name(), to.name(), meters, minutes);
	}

	// ',' and '/' separate the link's parts, so names lose them before encoding
	private static String routeName(String name) {
		return URLEncoder.encode(name.replace(',', ' ').replace('/', ' '), StandardCharsets.UTF_8).replace("+", "%20");
	}

	// haversine distance
	static int meters(double lat1, double lng1, double lat2, double lng2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLng = Math.toRadians(lng2 - lng1);
		double a = Math.pow(Math.sin(dLat / 2), 2)
				+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
		return (int) Math.round(2 * 6_371_000 * Math.asin(Math.sqrt(a)));
	}

	private record Stop(String name, double lat, double lng) {
	}

	private record Cached(Optional<GoogleInfo> info, Instant at) {
	}
}
