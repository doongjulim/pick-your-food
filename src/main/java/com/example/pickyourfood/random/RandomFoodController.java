package com.example.pickyourfood.random;

import com.example.pickyourfood.food.Food;
import com.example.pickyourfood.food.FoodCatalog;
import java.util.List;
import java.util.Random;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RandomFoodController {

	private final List<Food> foods;
	private final Random random = new Random();

	RandomFoodController(FoodCatalog catalog) {
		this.foods = catalog.all();
	}

	@GetMapping("/api/foods/random")
	Food random() {
		return foods.get(random.nextInt(foods.size()));
	}
}
