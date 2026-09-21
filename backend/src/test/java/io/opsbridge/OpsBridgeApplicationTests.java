package io.opsbridge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Full context smoke test on in-memory H2, independent of Postgres/Docker -- doesn't cover the Postgres-specific Flyway migrations.
@SpringBootTest
@ActiveProfiles("test")
class OpsBridgeApplicationTests {

    @Test
    void contextLoads() {
    }
}
