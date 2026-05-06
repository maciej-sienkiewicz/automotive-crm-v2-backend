package com.example.demo.trends.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.util.Base64

/**
 * Spring configuration for the DataForSEO Trends module.
 *
 * Registers a dedicated [RestClient] bean pre-configured with:
 * - Base URL
 * - HTTP Basic Auth header
 * - JSON content type
 */
@Configuration
@EnableConfigurationProperties(DataForSeoProperties::class)
class DataForSeoConfig {

    @Bean
    fun dataForSeoRestClient(props: DataForSeoProperties): RestClient {
        val credentials = "${props.login}:${props.password}"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray())

        return RestClient.builder()
            .baseUrl(props.baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic $encodedCredentials")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }
}

