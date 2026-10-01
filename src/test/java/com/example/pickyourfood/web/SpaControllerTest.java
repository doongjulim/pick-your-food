package com.example.pickyourfood.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SpaControllerTest {

	@Autowired
	MockMvc mvc;

	@Test
	void savedListOpenedDirectlyGetsTheFrontend() throws Exception {
		mvc.perform(get("/saved")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
	}

	@Test
	void theApiKeepsItsOwnPaths() throws Exception {
		mvc.perform(get("/api/saved/unknown")).andExpect(status().isNotFound()).andExpect(forwardedUrl(null));
	}
}
