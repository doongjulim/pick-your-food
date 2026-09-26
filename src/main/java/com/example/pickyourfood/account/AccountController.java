package com.example.pickyourfood.account;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountController {

	// only reached when logged in; SecurityConfig answers 401 otherwise
	@GetMapping("/api/me")
	Me me(@AuthenticationPrincipal OAuth2User user) {
		return new Me(user.getAttribute(AccountService.ACCOUNT_ID), user.getAttribute(AccountService.NICKNAME));
	}

	record Me(long id, String nickname) {
	}
}
