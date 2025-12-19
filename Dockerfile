# CartIQ Backend Dockerfile
# Optimized for CI/CD: JAR is pre-built by GitHub Actions with Maven caching
# This Dockerfile just packages the JAR into a minimal runtime image

# Production image (Google Distroless - minimal and secure)
FROM gcr.io/distroless/java17-debian12

WORKDIR /app

# Copy the pre-built JAR from GitHub Actions build
COPY cartiq-app/target/cartiq-app-*.jar app.jar

# Cloud Run uses PORT env variable (default 8080)
ENV PORT=8080
EXPOSE 8080

# JVM options via JAVA_TOOL_OPTIONS (distroless has no shell)
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Run the application (exec form required - no shell in distroless)
# Spring Boot reads PORT env var automatically via application.properties
ENTRYPOINT ["java", "-jar", "app.jar"]
