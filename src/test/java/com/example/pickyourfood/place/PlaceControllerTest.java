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

import com.example.pickyourfood.place.PlacesResponse.DateCourse;
import com.example.pickyourfood.place.PlacesResponse.Leg;
import com.example.pickyourfood.place.PlacesResponse.Origin;
import com.example.pickyourfood.place.PlacesResponse.Place;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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
		DateCourse course = new DateCourse(restaurant, null, null, List.of(leg, straight), "https://map.kakao.com/link/by/walk/a");
		when(places.search("탄탄멘", here)).thenReturn(new PlacesResponse(here, List.of(), List.of(), List.of(course)));

		mvc.perform(get("/api/places").param("food", "탄탄멘").param("lat", "37.54").param("lng", "127.05"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dateCourses[0].legs[0].path[1][0]").value(37.564))
				.andExpect(jsonPath("$.dateCourses[0].legs[0].path[1][1]").value(127.051))
				.andExpect(jsonPath("$.dateCourses[0].legs[1].path").value(nullValue()));
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
}
