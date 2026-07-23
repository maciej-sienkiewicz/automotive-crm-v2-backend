plugins {
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
    kotlin("jvm") version "2.0.0"
    kotlin("plugin.spring") version "2.0.0"
    kotlin("plugin.jpa") version "2.0.0"
}

group = "pl.detailing"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/milestone") }
    maven { url = uri("https://repo.spring.io/snapshot") }
    // KSeF SDK – GitHub Packages (requires a PAT with read:packages scope).
    // Set credentials via env vars: GITHUB_ACTOR + GITHUB_TOKEN
    // OR in ~/.gradle/gradle.properties:  gpr.user=<login>  gpr.key=<PAT>
    maven {
        url = uri("https://maven.pkg.github.com/CIRFMF/ksef-client-java")
        credentials {
            username = project.findProperty("gpr.user")?.toString() ?: System.getenv("GITHUB_ACTOR") ?: ""
            password = project.findProperty("gpr.key")?.toString() ?: System.getenv("GITHUB_TOKEN") ?: ""
        }
    }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.0.0")
    }
}

dependencies {
    // Spring AI – OpenAI chat + pgvector vector store (Spring AI 1.0.0 / Spring Boot 3.2.x)
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.session:spring-session-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("com.zaxxer:HikariCP")

    // Flyway for database migrations
    implementation("org.flywaydb:flyway-core")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.8.0")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // AWS S3 for document storage
    implementation(platform("software.amazon.awssdk:bom:2.21.0"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:sts")
    implementation("software.amazon.awssdk:cloudwatch")

    // Apache PDFBox for PDF form filling and manipulation
    implementation("org.apache.pdfbox:pdfbox:3.0.1")
    implementation("org.apache.pdfbox:fontbox:3.0.1")
    // EXIF metadata (photo orientation normalization for damage photos)
    implementation("com.drewnoakes:metadata-extractor:2.19.0")

    // BouncyCastle – PAdES qualified electronic seal (CMS/CAdES) + RFC 3161 qualified timestamps
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcutil-jdk18on:1.78.1")

    // KSeF SDK – official Java client for the Polish National e-Invoicing System
    implementation("pl.akmf.ksef-sdk:ksef-client:3.0.17")

    // SMSAPI Java SDK – automated SMS dispatch
    implementation("pl.smsapi:smsapi-lib:3.0.1")

    // JavaMail – transactional email dispatch (welcome emails, protocol attachments)
    implementation("com.sun.mail:jakarta.mail:2.0.1")

    // Observability: Spring Boot Actuator + Micrometer Prometheus registry + AOP for metrics instrumentation
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // GUS BIR integration – caching + resilience
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")
    implementation("io.github.resilience4j:resilience4j-retry:2.2.0")
    implementation("io.github.resilience4j:resilience4j-kotlin:2.2.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.mockk:mockk:1.13.10")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xcontext-receivers")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    mainClass.set("pl.detailing.crm.DetailingCrmApplicationKt")
}