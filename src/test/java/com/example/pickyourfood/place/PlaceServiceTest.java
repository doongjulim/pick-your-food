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
import com.example.pickyourfood.place.PlacesResponse.Origin;
import com.example.pickyourfood.place.PlacesResponse.Place;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

class PlaceServiceTest {

	private static final Origin ORIGIN = new Origin("성수동", 37.54, 127.05);

	private final KakaoClient kakao = mock(KakaoClient.class);
	private final GooglePlacesClient google = mock(GooglePlacesClient.class);
	private final PlaceService service = new PlaceService(kakao, google);

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
	void dateCourseStartsAtTheTopFamousPlace() {
		famousCandidatesAre(place("second", 37.55), place("top", 37.56));
		googleKnows("second", info(37.55, 4.0, 10));
		googleKnows("top", info(37.56, 4.0, 900));
		when(kakao.nearest(KakaoClient.CAFE, 37.56, 127.05, PlaceService.CAFE_RADIUS))
				.thenReturn(Optional.of(new KakaoPlace("c", "어니언", "카페", "카페 주소", 37.561, 127.05, 320, "https://place.map.kakao.com/c")));

		PlacesResponse.DateCourse course = service.search("ramen", ORIGIN).dateCourse();

		assertThat(course.restaurant().id()).isEqualTo("top");
		assertThat(course.cafe().name()).isEqualTo("어니언");
		assertThat(course.cafe().distanceMeters()).isEqualTo(320);
		assertThat(course.sight()).isNull();
		verify(kakao).nearest(KakaoClient.SIGHT, 37.56, 127.05, PlaceService.SIGHT_RADIUS);
	}

	@Test
	void noFamousPlacesMeansNoDateCourse() {
		PlacesResponse response = service.search("ramen", ORIGIN);

		assertThat(response.famous()).isEmpty();
		assertThat(response.dateCourse()).isNull();
		verify(kakao, never()).nearest(anyString(), anyDouble(), anyDouble(), anyInt());
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
}
