package com.example.pickyourfood.place;

import com.example.pickyourfood.place.TmapClient.Route;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

// TMAP walking routes, kept across restarts so a search doesn't spend the daily quota again
@Repository
class RouteRepository {

	private final JdbcClient jdbc;
	private final JsonMapper json;

	RouteRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	Optional<Route> find(String key, Instant since) {
		return jdbc.sql("select meters, seconds, path from walking_route where route_key = ? and created_at >= ?")
				.params(key, Timestamp.from(since))
				.query((rs, row) -> new Route(rs.getInt("meters"), rs.getInt("seconds"),
						List.of(json.readValue(rs.getString("path"), double[][].class))))
				.optional();
	}

	// stale rows go on every save, so the table stays at about a month of routes
	void save(String key, Route route) {
		Instant now = Instant.now();
		jdbc.sql("delete from walking_route where created_at < ?").param(Timestamp.from(now.minus(PlaceService.ROUTE_TTL))).update();
		try {
			jdbc.sql("insert into walking_route (route_key, meters, seconds, path, created_at) values (?, ?, ?, ?, ?)")
					.params(key, route.meters(), route.seconds(), json.writeValueAsString(route.path()), Timestamp.from(now))
					.update();
		} catch (DuplicateKeyException e) {
			// another search saved the same leg first; its route is just as good
		}
	}
}
