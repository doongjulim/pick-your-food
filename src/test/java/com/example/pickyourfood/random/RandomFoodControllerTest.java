package com.example.pickyourfood.random;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RandomFoodControllerTest {

	@Autowired
	MockMvc mvc;

	@Test
	void randomReturnsAFood() throws Exception {
		mvc.perform(get("/api/foods/random"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").isString());
	}
}
