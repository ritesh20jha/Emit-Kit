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
# Heap at 60% of the container limit: metaspace (about 160 MB), compiled code (about 50 MB) and thread
# stacks live outside it, and at 75% a full heap plus these went over a 1 GiB limit. The caps turn
# a metaspace or code cache leak into an error in the log instead of the kernel killing the container.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:MaxMetaspaceSize=256m", "-XX:ReservedCodeCacheSize=128m", "-jar", "/app/app.jar"]
