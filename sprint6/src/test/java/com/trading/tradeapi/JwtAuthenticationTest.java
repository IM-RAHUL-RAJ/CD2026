package com.trading.tradeapi;

import com.trading.tradeapi.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.context.TestPropertySource;

/**
 * Integration tests that exercise the JWT filter through the full Spring context.
 * Uses H2 in-memory database (configured in src/test/resources/application.yml).
 * Tokens are minted by TestTokenFactory using the test JWT_SECRET.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-that-is-at-least-32-bytes-long",
        "spring.kafka.bootstrap-servers=localhost:9092"
})
public class JwtAuthenticationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private MockMvc mockMvc;

    // Must match jwt.secret in src/test/resources/application.yml
    private static final String TEST_SECRET = "test-secret-key-that-is-at-least-32-bytes-long";

    @BeforeEach
    public void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(jwtAuthenticationFilter)
                .build();
    }

    @Test
    public void missingTokenReturns401Auth401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"))
                .andExpect(jsonPath("$.message").value("Unauthorised"));
    }

    @Test
    public void wrongSchemeReturns401Auth401() throws Exception {
        String token = TestTokenFactory.createToken(1L, TEST_SECRET, 3_600_000L);
        mockMvc.perform(get("/api/v1/accounts/1")
                        .header("Authorization", "Basic " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"))
                .andExpect(jsonPath("$.message").value("Unauthorised"));
    }

    @Test
    public void expiredTokenReturns401Auth401() throws Exception {
        // ttl = -1000ms => already expired
        String token = TestTokenFactory.createToken(1L, TEST_SECRET, -1_000L);
        mockMvc.perform(get("/api/v1/accounts/1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"))
                .andExpect(jsonPath("$.message").value("Unauthorised"));
    }

    @Test
    public void wrongSignatureReturns401Auth401() throws Exception {
        // signed with a different key => signature mismatch
        String token = TestTokenFactory.createToken(1L, "wrong-secret-key-that-is-at-least-32-bytes-long", 3_600_000L);
        mockMvc.perform(get("/api/v1/accounts/1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"))
                .andExpect(jsonPath("$.message").value("Unauthorised"));
    }

    @Test
    public void validTokenForWrongAccountReturns403Acc403() throws Exception {
        // token says accountId=1, but path is /api/v1/accounts/2
        String token = TestTokenFactory.createToken(1L, TEST_SECRET, 3_600_000L);
        mockMvc.perform(get("/api/v1/accounts/2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"))
                .andExpect(jsonPath("$.message").value("Account not active"));
    }
}
