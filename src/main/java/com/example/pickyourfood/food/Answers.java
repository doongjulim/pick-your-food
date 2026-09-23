package com.example.pickyourfood.food;

import java.util.Objects;

public record Answers(Situation situation, Mood mood, Category category, Hunger hunger, Taste taste) {

	public Answers {
		Objects.requireNonNull(situation, "situation");
		Objects.requireNonNull(mood, "mood");
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(hunger, "hunger");
		Objects.requireNonNull(taste, "taste");
	}

	public enum Situation { ALONE, FRIENDS, DATE, GROUP }

	public enum Mood { EXCITED, NORMAL, DOWN, STRESSED }

	public enum Category { KOREAN, CHINESE, JAPANESE, WESTERN, SNACK, ANY }

	public enum Hunger { LIGHT, MODERATE, STARVING }

	public enum Taste { SPICY, MILD, RICH, SWEET_SOUR }
}
