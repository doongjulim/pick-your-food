package com.example.pickyourfood.account;

import java.util.Map;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

// turns a Kakao login into one of our accounts; the session keeps only the account id and nickname
@Service
class AccountService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

	static final String ACCOUNT_ID = "accountId";
	static final String NICKNAME = "nickname";
	static final String FALLBACK_NICKNAME = "카카오 사용자";

	private final AccountRepository accounts;
	private final KakaoUnlink unlink;
	private final FindByIndexNameSessionRepository<? extends Session> sessions;
	private final DefaultOAuth2UserService kakao = new DefaultOAuth2UserService();

	AccountService(AccountRepository accounts, KakaoUnlink unlink, FindByIndexNameSessionRepository<? extends Session> sessions) {
		this.accounts = accounts;
		this.unlink = unlink;
		this.sessions = sessions;
	}

	@Override
	public OAuth2User loadUser(OAuth2UserRequest request) {
		OAuth2User user = kakao.loadUser(request);
		Account account = signIn(user.getName(), nickname(user.getAttributes()));
		return new DefaultOAuth2User(user.getAuthorities(),
				Map.of(ACCOUNT_ID, account.id(), NICKNAME, account.nickname()), ACCOUNT_ID);
	}

	// ponytail: two first logins of the same Kakao user at once hit the unique key; retrying the login is enough
	Account signIn(String kakaoId, String nickname) {
		return accounts.findByKakaoId(kakaoId)
				.map(found -> {
					if (!found.nickname().equals(nickname)) accounts.rename(found.id(), nickname);
					return new Account(found.id(), nickname);
				})
				.orElseGet(() -> accounts.create(kakaoId, nickname));
	}

	// Kakao first, so a failed call deletes nothing and can be retried; then the account,
	// its saved results and every session it is logged in with (the principal name is the account id)
	void delete(long accountId) {
		accounts.kakaoId(accountId).ifPresent(kakaoId -> {
			unlink.unlink(kakaoId);
			accounts.delete(accountId);
		});
		sessions.findByPrincipalName(String.valueOf(accountId)).keySet().forEach(sessions::deleteById);
	}

	// Kakao puts the nickname in properties, and in kakao_account.profile for newer apps
	static String nickname(Map<String, Object> attributes) {
		if (attributes.get("properties") instanceof Map<?, ?> properties
				&& properties.get("nickname") instanceof String name && !name.isBlank()) return name;
		if (attributes.get("kakao_account") instanceof Map<?, ?> account
				&& account.get("profile") instanceof Map<?, ?> profile
				&& profile.get("nickname") instanceof String name && !name.isBlank()) return name;
		return FALLBACK_NICKNAME;
	}
}
