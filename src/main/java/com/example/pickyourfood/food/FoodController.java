package com.example.pickyourfood.food;

import com.example.pickyourfood.food.Recommender.Recommendation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class FoodController {

	private final Recommender recommender;

	FoodController(Recommender recommender) {
		this.recommender = recommender;
	}

	@GetMapping("/foods/random")
	Food random() {
		return recommender.random();
	}

	@PostMapping("/recommendations")
	Recommendation recommend(@RequestBody Answers answers) {
		return recommender.recommend(answers);
	}
}
