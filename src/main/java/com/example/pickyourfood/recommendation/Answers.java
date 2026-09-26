package com.example.pickyourfood.recommendation;

import com.example.pickyourfood.food.Food.Category;
import com.example.pickyourfood.food.Food.Hunger;
import com.example.pickyourfood.food.Food.Mood;
import com.example.pickyourfood.food.Food.Situation;
import com.example.pickyourfood.food.Food.Taste;
import java.util.Objects;

public record Answers(Situation situation, Mood mood, Category category, Hunger hunger, Taste taste) {

	public Answers {
		Objects.requireNonNull(situation, "situation");
		Objects.requireNonNull(mood, "mood");
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(hunger, "hunger");
		Objects.requireNonNull(taste, "taste");
	}
}
