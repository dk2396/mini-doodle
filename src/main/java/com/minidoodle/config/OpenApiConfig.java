package com.minidoodle.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI miniDoodleOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Mini Doodle API")
                        .description("High-performance meeting scheduling platform. "
                                + "Manage time slots, schedule meetings, and query availability.")
                        .version("v1")
                        .contact(new Contact().name("Mini Doodle Team"))
                        .license(new License().name("MIT")));
    }
}
