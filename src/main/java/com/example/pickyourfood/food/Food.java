package com.example.pickyourfood.food;

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

	public enum Situation { ALONE, FRIENDS, DATE, GROUP }

	public enum Mood { EXCITED, NORMAL, DOWN, STRESSED }

	// ANY is only an answer ("no preference"); no food is tagged with it
	public enum Category { KOREAN, CHINESE, JAPANESE, WESTERN, SNACK, ANY }

	public enum Hunger { LIGHT, MODERATE, STARVING }

	public enum Taste { SPICY, MILD, RICH, SWEET_SOUR }
}
