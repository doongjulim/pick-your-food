package com.example.pickyourfood.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AccountController {

	private final AccountService accounts;

	AccountController(AccountService accounts) {
		this.accounts = accounts;
	}

	// only reached when logged in; SecurityConfig answers 401 otherwise
	@GetMapping("/api/me")
	Me me(@AuthenticationPrincipal OAuth2User user) {
		return new Me(user.getAttribute(AccountService.ACCOUNT_ID), user.getAttribute(AccountService.NICKNAME));
	}

	@DeleteMapping("/api/me")
	ResponseEntity<Void> delete(@AuthenticationPrincipal OAuth2User user, Authentication authentication,
			HttpServletRequest request, HttpServletResponse response) {
		try {
			accounts.delete(user.<Long>getAttribute(AccountService.ACCOUNT_ID));
		} catch (RestClientException e) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Kakao unlink failed", e);
		}
		new SecurityContextLogoutHandler().logout(request, response, authentication);
		return ResponseEntity.noContent().build();
	}

	record Me(long id, String nickname) {
	}
}
