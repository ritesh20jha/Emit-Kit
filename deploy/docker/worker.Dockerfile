# Production image for worker-app. Build context: backend/ (see docker-compose.prod.yml).
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :worker-app:bootJar \
 && mkdir -p /app \
 && mv "$(ls worker-app/build/libs/*.jar | grep -v -- '-plain.jar')" /app/app.jar

FROM eclipse-temurin:21-jre-noble
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin emitkit
COPY --from=build /app/app.jar /app/app.jar
USER 10001:10001
# No HTTP port: the worker only talks to RabbitMQ, Postgres, Redis, Vault and the sandbox socket.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
