package com.akshitanchan.saas.config;

import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// spring boot registers any Module bean into the primary ObjectMapper automatically, which is
// what lets JsonNullable<T> request fields distinguish an absent json key from an explicit null
@Configuration
public class JacksonConfig {

    @Bean
    public JsonNullableModule jsonNullableModule() {
        return new JsonNullableModule();
    }
}
