package com.example.pickyourfood.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// the frontend picks the page from the path, so a link opened directly gets the same index.html as /
@Controller
class SpaController {

	@GetMapping({ "/s/{id}", "/saved" })
	String page() {
		return "forward:/index.html";
	}
}
