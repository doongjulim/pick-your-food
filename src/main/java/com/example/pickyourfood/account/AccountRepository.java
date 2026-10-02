package com.example.pickyourfood.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class AccountRepository {

	private final JdbcClient jdbc;

	AccountRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Account> findByKakaoId(String kakaoId) {
		return jdbc.sql("select id, nickname from account where kakao_id = ?").param(kakaoId)
				.query((rs, row) -> new Account(rs.getLong("id"), rs.getString("nickname")))
				.optional();
	}

	Account create(String kakaoId, String nickname) {
		KeyHolder key = new GeneratedKeyHolder();
		jdbc.sql("insert into account (kakao_id, nickname, created_at) values (?, ?, ?)")
				.params(kakaoId, nickname, Timestamp.from(Instant.now()))
				.update(key, "id");
		return new Account(key.getKey().longValue(), nickname);
	}

	void rename(long id, String nickname) {
		jdbc.sql("update account set nickname = ? where id = ?").params(nickname, id).update();
	}

	Optional<String> kakaoId(long id) {
		return jdbc.sql("select kakao_id from account where id = ?").param(id).query(String.class).optional();
	}

	// saved results first: they reference the account
	@Transactional
	void delete(long id) {
		jdbc.sql("delete from saved_result where account_id = ?").param(id).update();
		jdbc.sql("delete from account where id = ?").param(id).update();
	}
}
