package com.example.pickyourfood.recommendation;

import com.example.pickyourfood.recommendation.Recommender.Recommendation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RecommendationController {

	private final Recommender recommender;

	RecommendationController(Recommender recommender) {
		this.recommender = recommender;
	}

	@PostMapping("/api/recommendations")
	Recommendation recommend(@RequestBody Answers answers) {
		return recommender.recommend(answers);
	}
}
