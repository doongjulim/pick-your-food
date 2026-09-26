package com.example.pickyourfood.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

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
}
