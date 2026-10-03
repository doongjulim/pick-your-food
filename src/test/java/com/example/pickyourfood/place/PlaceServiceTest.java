package com.example.pickyourfood.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.pickyourfood.place.GooglePlacesClient.GoogleInfo;
import com.example.pickyourfood.place.KakaoClient.KakaoPlace;
import com.example.pickyourfood.place.PlacesResponse.DateCourse;
import com.example.pickyourfood.place.PlacesResponse.Leg;
import com.example.pickyourfood.place.PlacesResponse.Origin;
import com.example.pickyourfood.place.PlacesResponse.Place;
import com.example.pickyourfood.place.TmapClient.Route;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

class PlaceServiceTest {

	private static final Origin ORIGIN = new Origin("성수동", 37.54, 127.05);

	private final KakaoClient kakao = mock(KakaoClient.class);
	private final GooglePlacesClient google = mock(GooglePlacesClient.class);
	// a mock answers Optional.empty(), so tests that don't stub it get straight-line legs
	private final TmapClient tmap = mock(TmapClient.class);
	private final PlaceService service = new PlaceService(kakao, google, tmap);

	private static KakaoPlace place(String id, double lat) {
		return new KakaoPlace(id, id, "중국요리", "주소 " + id, lat, 127.05, 100, "https://place.map.kakao.com/" + id);
	}

	private static KakaoPlace place(String id) {
		return place(id, 37.54);
	}

	private static GoogleInfo info(double lat, Double rating, Integer reviewCount) {
		return new GoogleInfo(lat, 127.05, rating, reviewCount, 2, List.of(), "https://maps.google.com/?q=" + lat);
	}

	private void nearbyAre(KakaoPlace... places) {
		when(kakao.restaurants(eq("ramen"), anyDouble(), anyDouble(), eq(PlaceService.NEARBY_RADIUS), eq("distance"), eq(PlaceService.LIST_SIZE)))
				.thenReturn(List.of(places));
	}

	private void famousCandidatesAre(KakaoPlace... places) {
		when(kakao.restaurants(eq("ramen"), anyDouble(), anyDouble(), eq(PlaceService.FAMOUS_RADIUS), eq("accuracy"), eq(PlaceService.FAMOUS_CANDIDATES)))
				.thenReturn(List.of(places));
	}

	// the service searches Google with "<name> <address>"; names in these tests are the ids
	private void googleKnows(String id, GoogleInfo info) {
		when(google.find(startsWith(id + " "), anyDouble(), anyDouble())).thenReturn(Optional.of(info));
	}

	// latitudes of the famous places in rank order, all at longitude 127.05
	private static final double[] RANKED_LATS = { 37.56, 37.57, 37.58, 37.59 };

	// famous places ranked in the given order: the first has the most reviews
	private void famousRanked(String... ids) {
		KakaoPlace[] places = new KakaoPlace[ids.length];
		for (int i = 0; i < ids.length; i++) {
			places[i] = place(ids[i], RANKED_LATS[i]);
			googleKnows(ids[i], info(RANKED_LATS[i], 4.0, 1000 - i * 100));
		}
		famousCandidatesAre(places);
	}

	private static KakaoPlace spot(String id, double lat) {
		return new KakaoPlace(id, id, "카페", "주소 " + id, lat, 127.05, 0, "https://place.map.kakao.com/" + id);
	}

	private void cafesNear(double lat, KakaoPlace... found) {
		when(kakao.nearby(KakaoClient.CAFE, lat, 127.05, PlaceService.CAFE_RADIUS)).thenReturn(List.of(found));
	}

	private void sightsNear(double lat, KakaoPlace... found) {
		when(kakao.nearby(KakaoClient.SIGHT, lat, 127.05, PlaceService.SIGHT_RADIUS)).thenReturn(List.of(found));
	}

	@Test
	void famousIsSortedByReviewCountThenRatingWithUnknownLast() {
		famousCandidatesAre(place("unknown"), place("few"), place("manyLow"), place("manyHigh"));
		googleKnows("few", info(37.54, 4.9, 10));
		googleKnows("manyLow", info(37.54, 4.0, 500));
		googleKnows("manyHigh", info(37.54, 4.5, 500));

		List<Place> famous = service.search("ramen", ORIGIN).famous();

		assertThat(famous).extracting(Place::id).containsExactly("manyHigh", "manyLow", "few", "unknown");
	}

	@Test
	void famousSkipsPlacesAlreadyNearbyAndKeepsFive() {
		nearbyAre(place("a"));
		famousCandidatesAre(place("a"), place("b"), place("c"), place("d"), place("e"), place("f"), place("g"));

		PlacesResponse response = service.search("ramen", ORIGIN);

		assertThat(response.nearby()).extracting(Place::id).containsExactly("a");
		assertThat(response.famous()).extracting(Place::id).containsExactlyInAnyOrder("b", "c", "d", "e", "f");
	}

	@Test
	void googleMatchFartherThan200mIsIgnored() {
		nearbyAre(place("close"), place("far"));
		googleKnows("close", info(37.541, 4.5, 100)); // about 111m away
		googleKnows("far", info(37.543, 4.5, 100)); // about 333m away

		List<Place> nearby = service.search("ramen", ORIGIN).nearby();

		assertThat(nearby.get(0).rating()).isEqualTo(4.5);
		assertThat(nearby.get(0).googleUrl()).isNotNull();
		assertThat(nearby.get(1).rating()).isNull();
		assertThat(nearby.get(1).reviewCount()).isNull();
		assertThat(nearby.get(1).priceLevel()).isNull();
		assertThat(nearby.get(1).reviews()).isEmpty();
		assertThat(nearby.get(1).googleUrl()).isNull();
	}

	@Test
	void googleFailureLeavesPlaceWithoutInfoAndIsRetriedNextTime() {
		nearbyAre(place("a"));
		when(google.find(startsWith("a "), anyDouble(), anyDouble()))
				.thenThrow(new RestClientException("boom"))
				.thenReturn(Optional.of(info(37.54, 4.2, 30)));

		assertThat(service.search("ramen", ORIGIN).nearby().get(0).rating()).isNull();
		assertThat(service.search("ramen", ORIGIN).nearby().get(0).rating()).isEqualTo(4.2);
	}

	@Test
	void cachedPlaceDoesNotCallGoogleAgain() {
		nearbyAre(place("a"));
		googleKnows("a", info(37.54, 4.2, 30));

		service.search("ramen", ORIGIN);
		PlacesResponse again = service.search("ramen", ORIGIN);

		assertThat(again.nearby().get(0).rating()).isEqualTo(4.2);
		verify(google, times(1)).find(startsWith("a "), anyDouble(), anyDouble());
	}

	@Test
	void threeCoursesStartAtTheTopThreeFamousPlaces() {
		famousRanked("r1", "r2", "r3", "r4");

		List<DateCourse> courses = service.search("ramen", ORIGIN).dateCourses();

		assertThat(courses).extracting(course -> course.restaurant().id()).containsExactly("r1", "r2", "r3");
		verify(kakao).nearby(KakaoClient.CAFE, 37.58, 127.05, PlaceService.CAFE_RADIUS);
		verify(kakao).nearby(KakaoClient.SIGHT, 37.58, 127.05, PlaceService.SIGHT_RADIUS);
		verify(kakao, never()).nearby(anyString(), eq(37.59), anyDouble(), anyInt());
	}

	@Test
	void twoFamousPlacesMakeTwoCourses() {
		famousRanked("r1", "r2");

		assertThat(service.search("ramen", ORIGIN).dateCourses()).hasSize(2);
	}

	@Test
	void noFamousPlacesMeansNoCourses() {
		PlacesResponse response = service.search("ramen", ORIGIN);

		assertThat(response.famous()).isEmpty();
		assertThat(response.dateCourses()).isEmpty();
		verify(kakao, never()).nearby(anyString(), anyDouble(), anyDouble(), anyInt());
	}

	@Test
	void laterCourseSkipsACafeAnEarlierCourseUses() {
		famousRanked("r1", "r2");
		cafesNear(37.56, spot("shared", 37.561));
		cafesNear(37.57, spot("shared", 37.561), spot("own", 37.572));

		List<DateCourse> courses = service.search("ramen", ORIGIN).dateCourses();

		assertThat(courses).extracting(course -> course.cafe().id()).containsExactly("shared", "own");
	}

	@Test
	void everyCandidateTakenFallsBackToTheNearest() {
		famousRanked("r1", "r2");
		cafesNear(37.56, spot("shared", 37.565));
		cafesNear(37.57, spot("shared", 37.565));

		List<DateCourse> courses = service.search("ramen", ORIGIN).dateCourses();

		assertThat(courses).extracting(course -> course.cafe().id()).containsExactly("shared", "shared");
	}

	@Test
	void legsMeasureWalkingDistanceRoundedUpToAMinute() {
		famousRanked("r1");
		cafesNear(37.56, spot("cafe", 37.564)); // 445m from the restaurant
		sightsNear(37.56, spot("sight", 37.569)); // 556m from the cafe

		DateCourse course = service.search("ramen", ORIGIN).dateCourses().get(0);

		assertThat(course.legs()).containsExactly(new Leg("r1", "cafe", 445, 7, null), new Leg("cafe", "sight", 556, 9, null));
	}

	@Test
	void withoutACafeTheSightIsReachedFromTheRestaurant() {
		famousRanked("r1");
		sightsNear(37.56, spot("sight", 37.56)); // same spot: 0m still takes a minute

		DateCourse course = service.search("ramen", ORIGIN).dateCourses().get(0);

		assertThat(course.cafe()).isNull();
		assertThat(course.legs()).containsExactly(new Leg("r1", "sight", 0, 1, null));
		assertThat(course.routeUrl()).isNotNull();
	}

	@Test
	void restaurantOnlyCourseHasNoLegsOrRoute() {
		famousRanked("r1");

		DateCourse course = service.search("ramen", ORIGIN).dateCourses().get(0);

		assertThat(course.cafe()).isNull();
		assertThat(course.sight()).isNull();
		assertThat(course.legs()).isEmpty();
		assertThat(course.routeUrl()).isNull();
	}

	@Test
	void failedCafeSearchLeavesOnlyThatStepEmpty() {
		famousRanked("r1");
		when(kakao.nearby(KakaoClient.CAFE, 37.56, 127.05, PlaceService.CAFE_RADIUS))
				.thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
		sightsNear(37.56, spot("sight", 37.569));

		DateCourse course = service.search("ramen", ORIGIN).dateCourses().get(0);

		assertThat(course.cafe()).isNull();
		assertThat(course.sight().id()).isEqualTo("sight");
	}

	@Test
	void routeUrlListsStopsInOrderWithEncodedNames() {
		famousRanked("탄탄 집");
		cafesNear(37.56, spot("a,b/c", 37.564));
		sightsNear(37.56, spot("서울숲", 37.569));

		DateCourse course = service.search("ramen", ORIGIN).dateCourses().get(0);

		assertThat(course.routeUrl()).isEqualTo("https://map.kakao.com/link/by/walk/"
				+ "%ED%83%84%ED%83%84%20%EC%A7%91,37.56,127.05/"
				+ "a%20b%20c,37.564,127.05/"
				+ "%EC%84%9C%EC%9A%B8%EC%88%B2,37.569,127.05");
	}

	@Test
	void locateUsesTheFirstKakaoMatch() {
		when(kakao.locate("성수동")).thenReturn(Optional.of(place("seongsu", 37.5445)));

		assertThat(service.locate("성수동")).isEqualTo(new Origin("성수동", 37.5445, 127.05));
	}

	@Test
	void unknownPlaceIsNotFound() {
		assertThatThrownBy(() -> service.locate("없는동네")).isInstanceOfSatisfying(ResponseStatusException.class,
				e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	private static final List<double[]> WALK = List.of(
			new double[] { 37.56, 127.05 }, new double[] { 37.562, 127.051 }, new double[] { 37.564, 127.05 });

	@Test
	void legsFollowTheWalkingRouteWhenTmapHasOne() {
		famousRanked("r1");
		cafesNear(37.56, spot("cafe", 37.564));
		when(tmap.route(37.56, 127.05, 37.564, 127.05)).thenReturn(Optional.of(new Route(512, 421, WALK)));

		Leg leg = service.search("ramen", ORIGIN).dateCourses().get(0).legs().get(0);

		assertThat(leg.meters()).isEqualTo(512);
		assertThat(leg.walkMinutes()).isEqualTo(8); // 421s rounds up
		assertThat(leg.path()).containsExactlyElementsOf(WALK);
	}

	@Test
	void failedRouteFallsBackToTheStraightLineAndIsNotCached() {
		famousRanked("r1");
		cafesNear(37.56, spot("cafe", 37.564)); // 445m from the restaurant
		when(tmap.route(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenThrow(new RestClientException("TMAP down"));

		service.search("ramen", ORIGIN);
		DateCourse course = service.search("ramen", ORIGIN).dateCourses().get(0);

		assertThat(course.legs()).containsExactly(new Leg("r1", "cafe", 445, 7, null));
		verify(tmap, times(2)).route(anyDouble(), anyDouble(), anyDouble(), anyDouble());
	}

	@Test
	void routesAreCachedPerPairOfStops() {
		famousRanked("r1");
		cafesNear(37.56, spot("cafe", 37.564));
		when(tmap.route(37.56, 127.05, 37.564, 127.05)).thenReturn(Optional.of(new Route(512, 421, WALK)));

		service.search("ramen", ORIGIN);
		Leg leg = service.search("ramen", ORIGIN).dateCourses().get(0).legs().get(0);

		assertThat(leg.meters()).isEqualTo(512);
		verify(tmap, times(1)).route(anyDouble(), anyDouble(), anyDouble(), anyDouble());
	}

	@Test
	void pathsAreRoundedAndLongOnesThinnedKeepingBothEnds() {
		assertThat(PlaceService.thin(List.of(new double[] { 37.123456789, 127.987654321 })))
				.containsExactly(new double[] { 37.12346, 127.98765 });

		List<double[]> longPath = IntStream.range(0, 1000).mapToObj(i -> new double[] { 37.5 + i * 0.000001234, 127.0 }).toList();
		List<double[]> thinned = PlaceService.thin(longPath);

		assertThat(thinned).hasSize(PlaceService.MAX_PATH_POINTS);
		assertThat(thinned.get(0)).containsExactly(37.5, 127.0);
		assertThat(thinned.get(thinned.size() - 1)).containsExactly(37.50123, 127.0); // the 1000th point, rounded
	}
}
