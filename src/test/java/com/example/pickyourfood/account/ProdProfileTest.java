package com.example.pickyourfood.account;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// on Render, HTTPS ends at its proxy and the app sees plain http with X-Forwarded-Proto
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProdProfileTest {

	@Autowired
	MockMvc mvc;

	@Test
	void kakaoRedirectUriUsesTheAddressThePublicSees() throws Exception {
		mvc.perform(get("/oauth2/authorization/kakao")
						.with(r -> { r.setServerName("pick.example"); r.setServerPort(80); return r; })
						.header("X-Forwarded-Proto", "https"))
				.andExpect(header().string("Location",
						containsString("redirect_uri=https://pick.example/login/oauth2/code/kakao")));
	}

	@Test
	void sessionCookieIsSecure() throws Exception {
		mvc.perform(get("/oauth2/authorization/kakao")).andExpect(cookie().secure("SESSION", true));
	}

	@Test
	void sharePreviewLinksUseTheAddressThePublicSees() throws Exception {
		mvc.perform(get("/s/Ab12Cd34Ef56Gh78Ij90Kl")
						.with(r -> { r.setServerName("pick.example"); r.setServerPort(80); return r; })
						.header("X-Forwarded-Proto", "https"))
				.andExpect(content().string(allOf(
						containsString("content=\"https://pick.example/og.png\""),
						containsString("content=\"https://pick.example/s/Ab12Cd34Ef56Gh78Ij90Kl\""))));
	}
}
