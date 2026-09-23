package com.example.pickyourfood.food;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pickyourfood.food.Answers.Category;
import com.example.pickyourfood.food.Answers.Hunger;
import com.example.pickyourfood.food.Answers.Mood;
import com.example.pickyourfood.food.Answers.Situation;
import com.example.pickyourfood.food.Answers.Taste;
import com.example.pickyourfood.food.Recommender.Recommendation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RecommenderTest {

	// only mood and taste vary; situation/hunger never match answers() below
	private static Food food(String id, Category category, Mood mood, Taste taste) {
		return new Food(id, id, id, category, Set.of(Situation.GROUP), Set.of(mood), Set.of(Hunger.LIGHT), Set.of(taste));
	}

	private static Answers answers(Category category) {
		return new Answers(Situation.ALONE, Mood.DOWN, category, Hunger.STARVING, Taste.SPICY);
	}

	@Test
	void keepsOnlyTheChosenCategory() {
		List<Food> foods = List.of(
				food("k1", Category.KOREAN, Mood.NORMAL, Taste.MILD),
				food("k2", Category.KOREAN, Mood.NORMAL, Taste.MILD),
				food("k3", Category.KOREAN, Mood.NORMAL, Taste.MILD),
				food("c1", Category.CHINESE, Mood.DOWN, Taste.SPICY));

		Recommendation result = new Recommender(foods, new Random(1)).recommend(answers(Category.KOREAN));

		assertThat(result.best().category()).isEqualTo(Category.KOREAN);
		assertThat(result.alternatives()).extracting(Food::category).containsOnly(Category.KOREAN);
	}

	@Test
	void anyCategoryConsidersEveryFood() {
		List<Food> foods = List.of(
				food("k1", Category.KOREAN, Mood.NORMAL, Taste.MILD),
				food("k2", Category.KOREAN, Mood.NORMAL, Taste.MILD),
				food("c1", Category.CHINESE, Mood.DOWN, Taste.SPICY));

		Recommendation result = new Recommender(foods, new Random(1)).recommend(answers(Category.ANY));

		assertThat(result.best().id()).isEqualTo("c1");
	}

	@Test
	void moodOutweighsTaste() {
		List<Food> foods = List.of(
				food("taste-only", Category.KOREAN, Mood.NORMAL, Taste.SPICY),
				food("mood-only", Category.KOREAN, Mood.DOWN, Taste.MILD),
				food("neither", Category.KOREAN, Mood.NORMAL, Taste.MILD));

		for (int seed = 0; seed < 20; seed++) {
			Recommendation result = new Recommender(foods, new Random(seed)).recommend(answers(Category.KOREAN));
			assertThat(result.best().id()).isEqualTo("mood-only");
			assertThat(result.alternatives()).extracting(Food::id).containsExactly("taste-only", "neither");
		}
	}

	@Test
	void tiesAreBrokenRandomly() {
		List<Food> foods = List.of(
				food("a", Category.KOREAN, Mood.DOWN, Taste.SPICY),
				food("b", Category.KOREAN, Mood.DOWN, Taste.SPICY),
				food("c", Category.KOREAN, Mood.DOWN, Taste.SPICY));

		Set<String> bests = new HashSet<>();
		for (int seed = 0; seed < 30; seed++) {
			bests.add(new Recommender(foods, new Random(seed)).recommend(answers(Category.KOREAN)).best().id());
		}

		assertThat(bests).hasSizeGreaterThan(1);
	}

	@Test
	void everyAnswerCombinationGivesThreeDistinctFoods() {
		Recommender recommender = new Recommender(new FoodCatalog(new JsonMapper()).all(), new Random(7));
		List<Answers> all = new ArrayList<>();
		for (Situation s : Situation.values())
			for (Mood m : Mood.values())
				for (Category c : Category.values())
					for (Hunger h : Hunger.values())
						for (Taste t : Taste.values())
							all.add(new Answers(s, m, c, h, t));

		assertThat(all).allSatisfy(answers -> {
			Recommendation result = recommender.recommend(answers);
			List<String> ids = new ArrayList<>();
			ids.add(result.best().id());
			result.alternatives().forEach(food -> ids.add(food.id()));
			assertThat(ids).as(answers.toString()).hasSize(3).doesNotHaveDuplicates();
		});
	}

	@Test
	void randomPicksFromTheCatalog() {
		List<Food> foods = List.of(food("only", Category.KOREAN, Mood.DOWN, Taste.SPICY));

		assertThat(new Recommender(foods, new Random()).random().id()).isEqualTo("only");
	}
}
