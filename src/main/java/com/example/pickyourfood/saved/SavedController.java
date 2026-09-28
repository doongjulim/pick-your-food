package com.example.pickyourfood.saved;

import com.example.pickyourfood.saved.SavedRepository.Stored;
import com.example.pickyourfood.saved.SavedRepository.Summary;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/saved")
class SavedController {

	private static final int MAX_PAYLOAD = 65_536;

	private final SavedRepository saved;
	private final JsonMapper json;

	SavedController(SavedRepository saved, JsonMapper json) {
		this.saved = saved;
		this.json = json;
	}

	// the principal's name is the account id (AccountService builds it that way)
	private static long accountId(Authentication auth) {
		return Long.parseLong(auth.getName());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	Created save(@RequestBody SavedResult result, Authentication auth) {
		if (!valid(result)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		String payload = json.writeValueAsString(result);
		if (payload.length() > MAX_PAYLOAD) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
		return new Created(saved.create(accountId(auth), result, payload));
	}

	@GetMapping
	List<Summary> mine(Authentication auth) {
		return saved.findByAccount(accountId(auth));
	}

	// public: anyone with the link sees it; auth is null for visitors
	@GetMapping("/{id}")
	View one(@PathVariable String id, Authentication auth) {
		Stored stored = saved.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		boolean mine = auth != null && auth.getName().equals(String.valueOf(stored.accountId()));
		return new View(id, stored.createdAt(), mine, json.readValue(stored.payload(), SavedResult.class));
	}

	// someone else's result answers 404 too, so ids of others' results can't be probed
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable String id, Authentication auth) {
		if (!saved.delete(id, accountId(auth))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
	}

	private static boolean valid(SavedResult r) {
		return r.title() != null && !r.title().isBlank() && r.title().length() <= 100
				&& r.best() != null && r.best().id() != null && r.best().name() != null && r.best().name().length() <= 100
				&& fits(r.alternatives(), 5)
				&& (r.places() == null || valid(r.places()));
	}

	private static boolean valid(SavedResult.Places p) {
		return p.origin() != null && p.origin().name() != null && p.origin().name().length() <= 200
				&& fits(p.nearby(), 10) && fits(p.famous(), 10) && fits(p.dateCourses(), 3)
				&& p.dateCourses().stream().allMatch(c -> c.restaurant() != null && fits(c.legs(), 2));
	}

	private static boolean fits(List<?> list, int max) {
		return list != null && list.size() <= max && !list.contains(null);
	}

	record Created(String id) {
	}

	record View(String id, Instant createdAt, boolean mine, SavedResult result) {
	}
}
