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
# Heap at 60% of the container limit: metaspace (about 160 MB), compiled code (about 50 MB) and thread
# stacks live outside it, and at 75% a full heap plus these went over a 1 GiB limit. The caps turn
# a metaspace or code cache leak into an error in the log instead of the kernel killing the container.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:MaxMetaspaceSize=256m", "-XX:ReservedCodeCacheSize=128m", "-jar", "/app/app.jar"]
