# Java Maven Template - Production Containerfile
# Multi-stage build: Maven 4 + Java 25 for build, JRE 25 for runtime

# =============================================================================
# Build Stage - Maven 4 + Java 25 (Eclipse Temurin)
# =============================================================================
FROM maven:4.0.0-rc-5-eclipse-temurin-25 AS builder

WORKDIR /build

# Copy Maven configuration first (for better layer caching)
COPY pom.xml .
COPY .mvn .mvn

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn package -Dshade -B -DskipTests

# =============================================================================
# Runtime Stage - Minimal JRE 25 (Eclipse Temurin)
# =============================================================================
FROM eclipse-temurin:25-jre-alpine

# Create non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy JAR from builder stage
COPY --from=builder --chown=appuser:appgroup /build/target/*-SNAPSHOT.jar /app/app.jar

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

# JVM options for containerized environment
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Entry point
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
