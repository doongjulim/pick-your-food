package com.example.pickyourfood.saved;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SavedRepository {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	SavedRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// the id is the share link, so it has to be unguessable: 16 random bytes, 22 url-safe characters
	String create(long accountId, SavedResult result, String payload) {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		String origin = result.places() == null ? null : result.places().origin().name();
		jdbc.sql("insert into saved_result (id, account_id, title, food_name, origin_name, payload, created_at) values (?, ?, ?, ?, ?, ?, ?)")
				.params(id, accountId, result.title(), result.best().name(), origin, payload, Timestamp.from(Instant.now()))
				.update();
		return id;
	}

	List<Summary> findByAccount(long accountId) {
		return jdbc.sql("select id, title, food_name, origin_name, created_at, shared from saved_result where account_id = ? order by created_at desc")
				.param(accountId)
				.query((rs, row) -> new Summary(rs.getString("id"), rs.getString("title"), rs.getString("food_name"),
						rs.getString("origin_name"), rs.getTimestamp("created_at").toInstant(), rs.getBoolean("shared")))
				.list();
	}

	Optional<Stored> find(String id) {
		return jdbc.sql("select account_id, title, shared, payload, created_at from saved_result where id = ?").param(id)
				.query((rs, row) -> new Stored(rs.getLong("account_id"), rs.getString("title"), rs.getBoolean("shared"),
						rs.getString("payload"), rs.getTimestamp("created_at").toInstant()))
				.optional();
	}

	// false when there is no such result or it belongs to someone else
	boolean delete(String id, long accountId) {
		return jdbc.sql("delete from saved_result where id = ? and account_id = ?").params(id, accountId).update() == 1;
	}

	// a null field stays as it is (only the set ones go in the SQL, so no untyped null reaches PostgreSQL);
	// false when there is no such result or it belongs to someone else
	boolean update(String id, long accountId, String title, Boolean shared) {
		List<String> sets = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		if (title != null) {
			sets.add("title = ?");
			params.add(title);
		}
		if (shared != null) {
			sets.add("shared = ?");
			params.add(shared);
		}
		params.add(id);
		params.add(accountId);
		return jdbc.sql("update saved_result set " + String.join(", ", sets) + " where id = ? and account_id = ?")
				.params(params).update() == 1;
	}

	record Summary(String id, String title, String foodName, String originName, Instant createdAt, boolean shared) {
	}

	// title is the current name: renaming changes the column, not the payload
	record Stored(long accountId, String title, boolean shared, String payload, Instant createdAt) {
	}
}
