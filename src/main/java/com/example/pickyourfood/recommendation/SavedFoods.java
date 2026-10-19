package com.example.pickyourfood.recommendation;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// reads the saved feature's table directly (like account does on deletion), so no feature imports another
@Repository
class SavedFoods {

	private final JdbcClient jdbc;

	SavedFoods(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// food name -> how many of the account's saved results have it as the best food
	Map<String, Integer> countsFor(long accountId) {
		return jdbc.sql("select food_name, count(*) as saves from saved_result where account_id = ? group by food_name")
				.param(accountId)
				.query((rs, row) -> Map.entry(rs.getString("food_name"), rs.getInt("saves")))
				// list(), not stream(): an unclosed stream keeps its connection
				.list().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}
}
