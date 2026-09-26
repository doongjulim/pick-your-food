package com.example.pickyourfood.recommendation;

import com.example.pickyourfood.food.Food;
import com.example.pickyourfood.food.Food.Category;
import com.example.pickyourfood.food.FoodCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class Recommender {

	static final int MOOD_WEIGHT = 4;
	static final int TASTE_WEIGHT = 3;
	static final int HUNGER_WEIGHT = 2;
	static final int SITUATION_WEIGHT = 2;

	private final List<Food> foods;
	private final Random random;

	@Autowired
	public Recommender(FoodCatalog catalog) {
		this(catalog.all(), new Random());
	}

	Recommender(List<Food> foods, Random random) {
		this.foods = foods;
		this.random = random;
	}

	public Recommendation recommend(Answers answers) {
		List<Food> candidates = new ArrayList<>(foods.stream()
				.filter(food -> answers.category() == Category.ANY || food.category() == answers.category())
				.toList());
		// shuffle first: the stable sort then keeps equal scores in random order
		Collections.shuffle(candidates, random);
		candidates.sort(Comparator.comparingInt((Food food) -> score(food, answers)).reversed());
		List<Food> top = candidates.subList(0, Math.min(3, candidates.size()));
		return new Recommendation(top.get(0), List.copyOf(top.subList(1, top.size())));
	}

	static int score(Food food, Answers answers) {
		int score = 0;
		if (food.moods().contains(answers.mood())) score += MOOD_WEIGHT;
		if (food.tastes().contains(answers.taste())) score += TASTE_WEIGHT;
		if (food.hunger().contains(answers.hunger())) score += HUNGER_WEIGHT;
		if (food.situations().contains(answers.situation())) score += SITUATION_WEIGHT;
		return score;
	}

	public record Recommendation(Food best, List<Food> alternatives) {
	}
}
