FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 spendings
WORKDIR /app
COPY --from=build /src/build/libs/spendings.jar app.jar
USER spendings
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
