package com.example.pickyourfood.recommendation;

import com.example.pickyourfood.food.Food;
import com.example.pickyourfood.food.Food.Category;
import com.example.pickyourfood.food.FoodCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToIntFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class Recommender {

	static final int MOOD_WEIGHT = 4;
	static final int TASTE_WEIGHT = 3;
	static final int HUNGER_WEIGHT = 2;
	static final int SITUATION_WEIGHT = 2;
	// alternatives may score this much below third place, so no answer always gets the same three foods
	static final int ALTERNATIVE_SLACK = 2;
	// a food the user saved gets one point per save, up to this; below a taste match, so answers still lead
	static final int SAVED_BONUS_MAX = 2;

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
		return recommend(answers, Map.of());
	}

	// savedCounts: how often the user saved each food as the best one, by food name
	public Recommendation recommend(Answers answers, Map<String, Integer> savedCounts) {
		ToIntFunction<Food> total = food -> score(food, answers) + savedBonus(savedCounts.getOrDefault(food.name(), 0));
		List<Food> candidates = new ArrayList<>(foods.stream()
				.filter(food -> answers.category() == Category.ANY || food.category() == answers.category())
				.toList());
		// shuffle first: the stable sort then keeps equal scores in random order
		Collections.shuffle(candidates, random);
		Comparator<Food> byScore = Comparator.comparingInt(total).reversed();
		candidates.sort(byScore);
		int floor = total.applyAsInt(candidates.get(Math.min(2, candidates.size() - 1))) - ALTERNATIVE_SLACK;
		List<Food> pool = new ArrayList<>(candidates.subList(1, candidates.size()).stream()
				.filter(food -> total.applyAsInt(food) >= floor)
				.toList());
		Collections.shuffle(pool, random);
		List<Food> alternatives = new ArrayList<>(pool.subList(0, Math.min(2, pool.size())));
		alternatives.sort(byScore);
		return new Recommendation(candidates.get(0), List.copyOf(alternatives));
	}

	static int savedBonus(int saves) {
		return Math.min(saves, SAVED_BONUS_MAX);
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
