package com.example.pickyourfood.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AccountServiceTest {

	@Autowired
	AccountService service;

	@Autowired
	AccountRepository accounts;

	@Test
	void newKakaoMemberGetsAnAccount() {
		Account account = service.signIn("service-new", "서윤");

		assertThat(account.nickname()).isEqualTo("서윤");
		assertThat(accounts.findByKakaoId("service-new")).contains(account);
	}

	@Test
	void returningMemberKeepsTheAccountAndGetsTheNewNickname() {
		Account first = service.signIn("service-again", "서윤");

		Account again = service.signIn("service-again", "윤서");

		assertThat(again).isEqualTo(new Account(first.id(), "윤서"));
		assertThat(accounts.findByKakaoId("service-again")).contains(again);
	}

	@Test
	void nicknameComesFromPropertiesThenProfileThenFallback() {
		assertThat(AccountService.nickname(Map.of("properties", Map.of("nickname", "서윤")))).isEqualTo("서윤");
		assertThat(AccountService.nickname(Map.of("kakao_account", Map.of("profile", Map.of("nickname", "하람")))))
				.isEqualTo("하람");
		assertThat(AccountService.nickname(Map.of("properties", Map.of("nickname", " ")))).isEqualTo("카카오 사용자");
		assertThat(AccountService.nickname(Map.of("id", 1))).isEqualTo("카카오 사용자");
	}
}
