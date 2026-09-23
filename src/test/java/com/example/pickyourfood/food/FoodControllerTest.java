package com.example.pickyourfood.food;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class FoodControllerTest {

	@Autowired
	MockMvc mvc;

	private ResultActions postRecommendation(String body) throws Exception {
		return mvc.perform(post("/api/recommendations").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	void randomReturnsAFood() throws Exception {
		mvc.perform(get("/api/foods/random"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").isString());
	}

	@Test
	void recommendationReturnsBestAndTwoAlternatives() throws Exception {
		postRecommendation("""
				{"situation":"ALONE","mood":"DOWN","category":"ANY","hunger":"MODERATE","taste":"SPICY"}""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.best.name").isString())
				.andExpect(jsonPath("$.alternatives.length()").value(2));
	}

	@Test
	void missingAnswerIsBadRequest() throws Exception {
		postRecommendation("""
				{"situation":"ALONE","category":"ANY","hunger":"MODERATE","taste":"SPICY"}""")
				.andExpect(status().isBadRequest());
	}

	@Test
	void unknownOrLowercaseValueIsBadRequest() throws Exception {
		postRecommendation("""
				{"situation":"alone","mood":"DOWN","category":"ANY","hunger":"MODERATE","taste":"SPICY"}""")
				.andExpect(status().isBadRequest());
		postRecommendation("""
				{"situation":"ALONE","mood":"HAPPY","category":"ANY","hunger":"MODERATE","taste":"SPICY"}""")
				.andExpect(status().isBadRequest());
	}

	@Test
	void emptyOrMalformedBodyIsBadRequest() throws Exception {
		postRecommendation("").andExpect(status().isBadRequest());
		postRecommendation("{oops").andExpect(status().isBadRequest());
	}
}
