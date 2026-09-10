package com.akshitanchan.saas;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// singleton containers: one postgres 16 and one redis 7 for the whole test run, started once and
// shared by every subclass. restarting them per test class (the previous @Container/@Testcontainers
// lifecycle) raced with spring's test-context cache, which could leave a later test class pointed at
// an already-stopped container from an earlier class. testcontainers' ryuk reaper still cleans these
// up when the jvm exits.
// the "test" profile layers src/test/resources/application-test.yaml on top of the main config
// (profile documents are always additive, never a replacement).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "/0");
    }
}
