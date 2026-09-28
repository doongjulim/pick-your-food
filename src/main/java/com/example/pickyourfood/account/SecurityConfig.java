package com.example.pickyourfood.account;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

@Configuration
class SecurityConfig {

	@Bean
	SecurityFilterChain security(HttpSecurity http, AccountService accounts) throws Exception {
		http
				.authorizeHttpRequests(requests -> requests
						.requestMatchers("/api/foods/**", "/api/recommendations", "/api/places").permitAll()
						// a saved result's link is public; saving, listing and deleting need a login
						.requestMatchers(HttpMethod.GET, "/api/saved/*").permitAll()
						.requestMatchers("/api/**").authenticated()
						.anyRequest().permitAll())
				// an API call without a login gets 401 instead of a redirect to a login page
				.exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				.oauth2Login(login -> login
						.userInfoEndpoint(userInfo -> userInfo.userService(accounts))
						.defaultSuccessUrl("/", true)
						.failureUrl("/?login=failed"))
				.logout(logout -> logout
						.logoutUrl("/api/logout")
						.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
				// the recommendation POST reads no session or account, so it needs no token
				.csrf(csrf -> csrf.spa().ignoringRequestMatchers("/api/recommendations"));
		return http.build();
	}
}
