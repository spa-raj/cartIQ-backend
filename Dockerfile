# CartIQ Backend Dockerfile
# Multi-stage build for modular monolith

# Stage 1: Build (Amazon Corretto - reliable alternative to Docker Hub images)
FROM amazoncorretto:17-alpine AS builder

WORKDIR /app

# Install Maven
RUN apk add --no-cache maven

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

# Stage 2: Production
FROM amazoncorretto:17-alpine

WORKDIR /app

# Create non-root user for security
RUN addgroup -S cartiq && adduser -S cartiq -G cartiq

# Copy the built JAR with proper ownership
COPY --from=builder --chown=cartiq:cartiq /app/cartiq-app/target/cartiq-app-*.jar app.jar

# Switch to non-root user
USER cartiq

# Cloud Run uses PORT env variable (default 8080)
ENV PORT=8080
EXPOSE 8080

# JVM options for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=$PORT"]
