# ---- build stage ----
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /src/target/research-summarizer-agent-*.jar app.jar
USER app
EXPOSE 8080
# API keys are injected at runtime as environment variables (docker compose env_file), never baked in.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
