package com.example.pickyourfood.food;

import com.example.pickyourfood.food.Answers.Category;
import com.example.pickyourfood.food.Answers.Hunger;
import com.example.pickyourfood.food.Answers.Mood;
import com.example.pickyourfood.food.Answers.Situation;
import com.example.pickyourfood.food.Answers.Taste;
import java.util.Set;

public record Food(
		String id,
		String name,
		String description,
		Category category,
		Set<Situation> situations,
		Set<Mood> moods,
		Set<Hunger> hunger,
		Set<Taste> tastes) {
}
