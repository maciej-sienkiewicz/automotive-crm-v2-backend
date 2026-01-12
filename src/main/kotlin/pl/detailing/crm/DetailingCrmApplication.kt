// src/main/kotlin/pl/detailing/crm/DetailingCrmApplication.kt

package pl.detailing.crm

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.annotation.EnableTransactionManagement

@SpringBootApplication
@EnableTransactionManagement
@EnableScheduling
class DetailingCrmApplication

fun main(args: Array<String>) {
    runApplication<DetailingCrmApplication>(*args)
}