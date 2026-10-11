import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spotless)
}

group = "com.spendings"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    val bootBom = platform(SpringBootPlugin.BOM_COORDINATES)
    implementation(bootBom)
    developmentOnly(bootBom)
    implementation(platform(libs.spring.modulith.bom))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-webauthn")  // passkeys
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    implementation(libs.webjars.htmx)
    implementation(libs.webjars.alpinejs)
    implementation(libs.webjars.bootstrap)
    implementation(libs.webjars.sortablejs)
    implementation(libs.webjars.echarts)
    implementation(libs.fastexcel)
    implementation(libs.fastexcel.reader)
    implementation(libs.webjars.locator)

    runtimeOnly(libs.postgresql)

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform { excludeTags("perf") }
}

// Скорость на 50 тысячах операций: ./gradlew perfTest
tasks.register<Test>("perfTest") {
    description = "Проверка скорости страниц на большом объёме данных."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("perf") }
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

tasks.bootJar {
    archiveFileName = "spendings.jar"
}

spotless {
    java {
        googleJavaFormat()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
