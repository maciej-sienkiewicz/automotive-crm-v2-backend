package pl.detailing.crm.livemetrics.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.stream.StreamMessageListenerContainer
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import pl.detailing.crm.livemetrics.platform.PlatformKeyInterceptor
import pl.detailing.crm.livemetrics.stream.BusinessEventStreamConsumer
import java.time.Duration

@Configuration
@EnableConfigurationProperties(LiveMetricsProperties::class)
class LiveMetricsConfig(
    private val platformKeyInterceptor: PlatformKeyInterceptor
) : WebMvcConfigurer {

    private val log = LoggerFactory.getLogger(LiveMetricsConfig::class.java)

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(platformKeyInterceptor)
            .addPathPatterns("/api/internal/**")
            .order(-100)
    }

    /**
     * Kontener nasłuchujący strumienia `lm:events` od „teraz” (`$`). Start/stop
     * zarządza Spring (`SmartLifecycle`), więc konsument żyje tak długo jak instancja.
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    fun liveMetricsStreamContainer(
        connectionFactory: RedisConnectionFactory,
        consumer: BusinessEventStreamConsumer,
        properties: LiveMetricsProperties
    ): StreamMessageListenerContainer<String, MapRecord<String, String, String>> {
        val options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
            .pollTimeout(Duration.ofSeconds(1))
            .batchSize(100)
            .errorHandler { e -> log.warn("[LIVE-METRICS] stream poll error: {}", e.toString()) }
            .build()
        val container = StreamMessageListenerContainer.create(connectionFactory, options)
        if (properties.enabled) {
            container.receive(StreamOffset.create(BusinessEventStreamConsumer.STREAM, ReadOffset.latest()), consumer)
        }
        return container
    }
}
