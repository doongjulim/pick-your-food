package com.example.pickyourfood.place;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pickyourfood.place.TmapClient.Route;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class RouteRepositoryTest {

	private static final List<double[]> PATH = List.of(new double[] { 37.56, 127.05 }, new double[] { 37.564, 127.05 });

	@Autowired
	RouteRepository routes;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	DataSource dataSource;

	@BeforeEach
	void empty() {
		jdbc.sql("delete from walking_route").update();
	}

	private static Instant since() {
		return Instant.now().minus(PlaceService.ROUTE_TTL);
	}

	private void storedAt(String key, Instant at) {
		jdbc.sql("insert into walking_route (route_key, meters, seconds, path, created_at) values (?, ?, ?, ?, ?)")
				.params(key, 1, 60, "[[37.0,127.0]]", Timestamp.from(at))
				.update();
	}

	@Test
	void savedRouteIsReadBack() {
		routes.save("a>b", new Route(512, 421, PATH));

		Route route = routes.find("a>b", since()).orElseThrow();

		assertThat(route.meters()).isEqualTo(512);
		assertThat(route.seconds()).isEqualTo(421);
		assertThat(route.path()).containsExactlyElementsOf(PATH);
	}

	@Test
	void unreachableDatabaseFailsWithinFiveSeconds() {
		HikariDataSource hikari = (HikariDataSource) dataSource;

		assertThat(hikari.getConnectionTimeout()).isEqualTo(5000);
	}

	@Test
	void unknownKeyIsEmpty() {
		assertThat(routes.find("nowhere", since())).isEmpty();
	}

	@Test
	void routeOlderThanSinceIsNotRead() {
		storedAt("old", Instant.now().minus(Duration.ofDays(31)));

		assertThat(routes.find("old", since())).isEmpty();
	}

	@Test
	void savingDeletesRoutesOlderThanTheTtl() {
		storedAt("old", Instant.now().minus(Duration.ofDays(31)));
		storedAt("recent", Instant.now().minus(Duration.ofDays(29)));

		routes.save("new", new Route(512, 421, PATH));

		assertThat(jdbc.sql("select route_key from walking_route").query(String.class).list())
				.containsExactlyInAnyOrder("new", "recent");
	}

	@Test
	void savingTheSameKeyTwiceKeepsTheFirst() {
		routes.save("a>b", new Route(512, 421, PATH));
		routes.save("a>b", new Route(1, 60, PATH));

		assertThat(routes.find("a>b", since()).orElseThrow().meters()).isEqualTo(512);
	}

	@Test
	void longestKeyAndPathFit() {
		// ids are capped at 100 characters by PlaceController; coordinates print up to 17 digits
		String id = "가".repeat(100);
		String key = id + "@37.123456789012345,127.12345678901234>" + id + "@37.123456789012345,127.12345678901234";
		List<double[]> path = Collections.nCopies(PlaceService.MAX_PATH_POINTS, new double[] { 37.12345, 127.12345 });

		routes.save(key, new Route(9_999, 9_999, path));

		assertThat(routes.find(key, since()).orElseThrow().path()).hasSize(PlaceService.MAX_PATH_POINTS);
	}
}
