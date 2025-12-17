# CartIQ Backend Dockerfile
# Multi-stage build for modular monolith
# Using Microsoft MCR + Google Distroless to avoid Docker Hub auth issues

# Stage 1: Build (Microsoft OpenJDK - hosted on MCR, not Docker Hub)
FROM mcr.microsoft.com/openjdk/jdk:17-ubuntu AS builder

WORKDIR /app

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Copy parent POM and all module POMs first (for dependency caching)
COPY pom.xml .
COPY cartiq-common/pom.xml cartiq-common/
COPY cartiq-user/pom.xml cartiq-user/
COPY cartiq-product/pom.xml cartiq-product/
COPY cartiq-order/pom.xml cartiq-order/
COPY cartiq-kafka/pom.xml cartiq-kafka/
COPY cartiq-ai/pom.xml cartiq-ai/
COPY cartiq-rag/pom.xml cartiq-rag/
COPY cartiq-seeder/pom.xml cartiq-seeder/
COPY cartiq-app/pom.xml cartiq-app/

# Download dependencies (cached layer) - only for cartiq-app and its deps
RUN mvn dependency:go-offline -pl cartiq-app -am -B

# Copy source code only for modules that cartiq-app depends on
COPY cartiq-common/src cartiq-common/src
COPY cartiq-user/src cartiq-user/src
COPY cartiq-product/src cartiq-product/src
COPY cartiq-order/src cartiq-order/src
COPY cartiq-kafka/src cartiq-kafka/src
COPY cartiq-ai/src cartiq-ai/src
COPY cartiq-app/src cartiq-app/src

# Build only cartiq-app and its dependencies (excludes seeder, rag)
RUN mvn clean package -pl cartiq-app -am -DskipTests -B

# Stage 2: Production (Google Distroless - minimal and secure)
FROM gcr.io/distroless/java17-debian12

WORKDIR /app

# Copy the built JAR
COPY --from=builder /app/cartiq-app/target/cartiq-app-*.jar app.jar

# Cloud Run uses PORT env variable (default 8080)
ENV PORT=8080
EXPOSE 8080

# JVM options via JAVA_TOOL_OPTIONS (distroless has no shell)
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Run the application (exec form required - no shell in distroless)
# Spring Boot reads PORT env var automatically via application.properties
ENTRYPOINT ["java", "-jar", "app.jar"]
