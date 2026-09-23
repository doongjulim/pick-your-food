package com.example.pickyourfood.food;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class FoodCatalog {

	private final List<Food> foods;

	public FoodCatalog(JsonMapper mapper) {
		this.foods = List.of(mapper.readValue(FoodCatalog.class.getResourceAsStream("/foods.json"), Food[].class));
	}

	public List<Food> all() {
		return foods;
	}
}
