package com.example.pickyourfood.place;

import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.pickyourfood.place.PlaceService.Stop;
import com.example.pickyourfood.place.PlacesResponse.Course;
import com.example.pickyourfood.place.PlacesResponse.DateCourse;
import com.example.pickyourfood.place.PlacesResponse.Leg;
import com.example.pickyourfood.place.PlacesResponse.Origin;
import com.example.pickyourfood.place.PlacesResponse.Place;
import com.example.pickyourfood.place.PlacesResponse.Spot;
import com.example.pickyourfood.place.PlacesResponse.Walk;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = "kakao.js-key=test-js-key")
@AutoConfigureMockMvc
class PlaceControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	PlaceService places;

	private static PlacesResponse empty(Origin origin) {
		return new PlacesResponse(origin, List.of(), List.of(), List.of());
	}

	@Test
	void coordinatesSearchFromTheCurrentLocation() throws Exception {
		Origin here = new Origin("현재 위치", 37.54, 127.05);
		when(places.search("탄탄멘", here)).thenReturn(empty(here));

		mvc.perform(get("/api/places").param("food", "탄탄멘").param("lat", "37.54").param("lng", "127.05"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.origin.name").value("현재 위치"))
				.andExpect(jsonPath("$.nearby").isArray())
				.andExpect(jsonPath("$.dateCourses").isArray());
		verify(places).search("탄탄멘", here);
	}

	@Test
	void nearIsResolvedToAnOrigin() throws Exception {
		Origin seongsu = new Origin("성수동", 37.5445, 127.0557);
		when(places.locate("성수동")).thenReturn(seongsu);
		when(places.search("탄탄멘", seongsu)).thenReturn(empty(seongsu));

		mvc.perform(get("/api/places").param("food", "탄탄멘").param("near", " 성수동 "))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.origin.name").value("성수동"));
	}

	@Test
	void missingFoodOrLocationIsBadRequest() throws Exception {
		mvc.perform(get("/api/places").param("near", "성수동")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/places").param("food", " ").param("near", "성수동")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/places").param("food", "탄탄멘")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/places").param("food", "탄탄멘").param("lat", "37.54")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/places").param("food", "탄탄멘").param("near", " ")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/places").param("food", "탄탄멘").param("lat", "north").param("lng", "127.05"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(places);
	}

	@Test
	void unknownPlaceIsNotFound() throws Exception {
		when(places.locate("없는동네")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

		mvc.perform(get("/api/places").param("food", "탄탄멘").param("near", "없는동네")).andExpect(status().isNotFound());
	}

	@Test
	void kakaoFailureIsBadGateway() throws Exception {
		when(places.search(any(), any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));

		mvc.perform(get("/api/places").param("food", "탄탄멘").param("lat", "37.54").param("lng", "127.05"))
				.andExpect(status().isBadGateway());
	}

	@Test
	void legPathIsSentAsLatLngPairs() throws Exception {
		Origin here = new Origin("현재 위치", 37.54, 127.05);
		Place restaurant = new Place("r1", "r1", "", 0, 37.56, 127.05, "", null, null, null, List.of(), null);
		Leg leg = new Leg("r1", "cafe", 512, 8, List.of(new double[] { 37.56, 127.05 }, new double[] { 37.564, 127.051 }));
		Leg straight = new Leg("cafe", "sight", 300, 5, null);
		DateCourse course = new DateCourse(restaurant, null, null, List.of(leg, straight), "https://map.kakao.com/link/by/walk/a", List.of(), List.of());
		when(places.search("탄탄멘", here)).thenReturn(new PlacesResponse(here, List.of(), List.of(), List.of(course)));

		mvc.perform(get("/api/places").param("food", "탄탄멘").param("lat", "37.54").param("lng", "127.05"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dateCourses[0].legs[0].path[1][0]").value(37.564))
				.andExpect(jsonPath("$.dateCourses[0].legs[0].path[1][1]").value(127.051))
				.andExpect(jsonPath("$.dateCourses[0].legs[1].path").value(nullValue()));
	}

	@Test
	void pickedCourseNeedsNoLogin() throws Exception {
		Spot cafe = new Spot("c1", "어니언", "카페", "", 37.554, 127.05, "", 120);
		Leg leg = new Leg("고른 식당", "어니언", 445, 7, null);
		when(places.course("p1", "고른 식당", 37.55, 127.05))
				.thenReturn(new Course(cafe, null, List.of(leg), "https://map.kakao.com/link/by/walk/a", List.of(cafe), List.of()));

		mvc.perform(get("/api/places/course").param("id", "p1").param("name", " 고른 식당 ").param("lat", "37.55").param("lng", "127.05"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cafe.name").value("어니언"))
				.andExpect(jsonPath("$.sight").value(nullValue()))
				.andExpect(jsonPath("$.cafes[0].distanceMeters").value(120))
				.andExpect(jsonPath("$.sights").isEmpty())
				.andExpect(jsonPath("$.legs[0].walkMinutes").value(7))
				.andExpect(jsonPath("$.routeUrl").value("https://map.kakao.com/link/by/walk/a"))
				.andExpect(jsonPath("$.restaurant").doesNotExist());
	}

	@Test
	void pickedCourseChecksItsInput() throws Exception {
		String[][] bad = {
				{ " ", "식당", "37.55", "127.05" },
				{ "p1", " ", "37.55", "127.05" },
				{ "x".repeat(101), "식당", "37.55", "127.05" },
				{ "p1", "가".repeat(101), "37.55", "127.05" },
				{ "p1", "식당", "91", "127.05" },
				{ "p1", "식당", "37.55", "-181" },
				{ "p1", "식당", "NaN", "127.05" },
				{ "p1", "식당", "37.55", "Infinity" },
				{ "p1", "식당", "north", "127.05" },
				{ "p1", "식당\n", "37.55", "127.05" },
				{ "p1\n", "식당", "37.55", "127.05" },
		};
		for (String[] params : bad) {
			mvc.perform(get("/api/places/course")
							.param("id", params[0]).param("name", params[1]).param("lat", params[2]).param("lng", params[3]))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(get("/api/places/course").param("id", "p1").param("name", "식당").param("lng", "127.05"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(places);
	}

	@Test
	void mapKeyNeedsNoLogin() throws Exception {
		mvc.perform(get("/api/places/map-key"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.key").value("test-js-key"));
	}

	@Test
	void mapKeyIsNullWithoutAKey() {
		assertThat(new PlaceController(places, "").mapKey().key()).isNull();
	}

	// stops as "|"-separated values per parameter, in order
	private static MockHttpServletRequestBuilder legs(String ids, String names, String lats, String lngs) {
		return get("/api/places/legs")
				.param("id", ids.split("\\|", -1)).param("name", names.split("\\|", -1))
				.param("lat", lats.split("\\|", -1)).param("lng", lngs.split("\\|", -1));
	}

	@Test
	void walkNeedsNoLogin() throws Exception {
		Leg first = new Leg("고른 식당", "어니언, 성수", 445, 7, null);
		Leg second = new Leg("어니언, 성수", "서울숲", 600, 9, null);
		when(places.walk(List.of(new Stop("p1", "고른 식당", 37.55, 127.05), new Stop("c1", "어니언, 성수", 37.554, 127.05),
				new Stop("s1", "서울숲", 37.559, 127.05))))
				.thenReturn(new Walk(List.of(first, second), "https://map.kakao.com/link/by/walk/a"));

		mvc.perform(legs("p1|c1|s1", " 고른 식당 |어니언, 성수|서울숲", "37.55|37.554|37.559", "127.05|127.05|127.05"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.legs[0].to").value("어니언, 성수"))
				.andExpect(jsonPath("$.legs[1].walkMinutes").value(9))
				.andExpect(jsonPath("$.routeUrl").value("https://map.kakao.com/link/by/walk/a"));
	}

	@Test
	void walkChecksItsInput() throws Exception {
		MockHttpServletRequestBuilder[] bad = {
				legs("p1", "식당", "37.55", "127.05"),
				legs("a|b|c|d", "가|나|다|라", "37.55|37.55|37.55|37.55", "127.05|127.05|127.05|127.05"),
				legs("p1|c1", "식당", "37.55|37.554", "127.05|127.05"),
				legs("p1|c1", "식당|카페", "37.55", "127.05|127.05"),
				legs(" |c1", "식당|카페", "37.55|37.554", "127.05|127.05"),
				legs("p1|c1", "식당|" + "가".repeat(101), "37.55|37.554", "127.05|127.05"),
				legs("p1|c1", "식당|카페\n", "37.55|37.554", "127.05|127.05"),
				legs("p1|c1", "식당|카페", "37.55|91", "127.05|127.05"),
				legs("p1|c1", "식당|카페", "37.55|37.554", "127.05|-181"),
				legs("p1|c1", "식당|카페", "37.55|NaN", "127.05|127.05"),
				legs("p1|c1", "식당|카페", "37.55|north", "127.05|127.05"),
				get("/api/places/legs").param("id", "p1", "c1").param("name", "식당", "카페").param("lat", "37.55", "37.554"),
		};
		for (MockHttpServletRequestBuilder request : bad) mvc.perform(request).andExpect(status().isBadRequest());
		verifyNoInteractions(places);
	}
}
