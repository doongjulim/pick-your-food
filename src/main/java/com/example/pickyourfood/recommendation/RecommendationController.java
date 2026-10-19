package com.example.pickyourfood.recommendation;

import com.example.pickyourfood.recommendation.Recommender.Recommendation;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RecommendationController {

	private final Recommender recommender;
	private final SavedFoods savedFoods;

	RecommendationController(Recommender recommender, SavedFoods savedFoods) {
		this.recommender = recommender;
		this.savedFoods = savedFoods;
	}

	// auth is null for visitors; the principal's name is the account id (AccountService builds it that way)
	@PostMapping("/api/recommendations")
	Recommendation recommend(@RequestBody Answers answers, Authentication auth) {
		Map<String, Integer> saved = auth == null ? Map.of() : savedFoods.countsFor(Long.parseLong(auth.getName()));
		return recommender.recommend(answers, saved);
	}
}
