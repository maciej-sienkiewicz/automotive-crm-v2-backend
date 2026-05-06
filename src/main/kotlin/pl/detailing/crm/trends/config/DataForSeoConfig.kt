package pl.detailing.crm.trends.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.util.Base64

@Configuration
@EnableConfigurationProperties(DataForSeoProperties::class)
class DataForSeoConfig {

    @Bean
    fun dataForSeoRestClient(props: DataForSeoProperties): RestClient {
        val encoded = Base64.getEncoder()
            .encodeToString("${props.login}:${props.password}".toByteArray())

        return RestClient.builder()
            .baseUrl(props.baseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic $encoded")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build()
    }
}
