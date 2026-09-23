package com.example.pickyourfood.food;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pickyourfood.food.Answers.Category;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FoodCatalogTest {

	private final List<Food> foods = new FoodCatalog(new JsonMapper()).all();

	@Test
	void everyCategoryHasAtLeastThreeFoods() {
		Map<Category, Long> counts = foods.stream().collect(Collectors.groupingBy(Food::category, Collectors.counting()));
		Arrays.stream(Category.values())
				.filter(category -> category != Category.ANY)
				.forEach(category -> assertThat(counts.getOrDefault(category, 0L)).as(category.name()).isGreaterThanOrEqualTo(3));
	}

	@Test
	void idsAreUnique() {
		assertThat(foods).extracting(Food::id).doesNotHaveDuplicates();
	}

	@Test
	void everyFoodIsFullyTagged() {
		assertThat(foods).allSatisfy(food -> {
			assertThat(food.name()).as(food.id()).isNotBlank();
			assertThat(food.description()).as(food.id()).isNotBlank();
			assertThat(food.category()).as(food.id()).isNotNull().isNotEqualTo(Category.ANY);
			assertThat(food.situations()).as(food.id()).isNotEmpty();
			assertThat(food.moods()).as(food.id()).isNotEmpty();
			assertThat(food.hunger()).as(food.id()).isNotEmpty();
			assertThat(food.tastes()).as(food.id()).isNotEmpty();
		});
	}
}
