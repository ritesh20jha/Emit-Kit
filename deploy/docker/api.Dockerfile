# Production image for api-app. Build context: backend/ (see docker-compose.prod.yml).
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :api-app:bootJar \
 && mkdir -p /app \
 && mv "$(ls api-app/build/libs/*.jar | grep -v -- '-plain.jar')" /app/app.jar

FROM eclipse-temurin:21-jre-noble
# curl is for the Compose healthcheck. The app runs as a fixed non-root uid.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin emitkit
COPY --from=build /app/app.jar /app/app.jar
USER 10001:10001
# 8080 serves the API; 8081 is the management port (health only). Neither is published on the host.
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
