package org.newsportal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NewsPortalBackendApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void healthEndpointReportsUpWithoutDetails() throws Exception {
		mockMvc.perform(get("/api/v1/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.components").doesNotExist());
	}

	@Test
	void flywaySeedsTheTwelveInitialCategories() {
		List<String> codes = jdbc.queryForList("SELECT code FROM category ORDER BY sort_order", String.class);
		assertThat(codes).containsExactly("world", "national", "politics", "business", "technology", "science",
				"health", "education", "sports", "entertainment", "environment", "lifestyle");
	}

	@Test
	void unknownApiPathRequiresSignIn() throws Exception {
		mockMvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isUnauthorized());
	}

}
