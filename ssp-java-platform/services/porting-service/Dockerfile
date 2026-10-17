# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy parent POM and shared module first (layer cache optimisation)
COPY pom.xml ./
COPY shared/common-models/pom.xml shared/common-models/
COPY shared/common-models/src     shared/common-models/src/

# Copy service POM and source
COPY services/porting-service/pom.xml services/porting-service/
COPY services/porting-service/src     services/porting-service/src/

# Download dependencies (separate layer for caching)
RUN mvn dependency:go-offline -B -pl services/porting-service -am -q

# Build the fat JAR
RUN mvn clean package -pl services/porting-service -am \
    -DskipTests -q \
    -Dmaven.test.skip=true

# Extract layered JAR for optimal Docker layer caching
WORKDIR /build/services/porting-service/target/dependency
RUN java -Djarmode=layertools -jar /build/services/porting-service/target/*.jar extract

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

ARG BUILD_DATE
ARG GIT_SHA
ARG SERVICE_VERSION="1.0.0"

LABEL org.opencontainers.image.created="${BUILD_DATE}"          \
      org.opencontainers.image.revision="${GIT_SHA}"             \
      org.opencontainers.image.title="ssp-porting-service"       \
      org.opencontainers.image.version="${SERVICE_VERSION}"       \
      org.opencontainers.image.vendor="NexaTel (fictitious)"

# Security: non-root user
RUN addgroup -g 1001 appgroup && \
    adduser  -u 1001 -G appgroup -s /sbin/nologin -D appuser

# Security: minimal packages only
RUN apk add --no-cache curl && \
    rm -rf /var/cache/apk/*

WORKDIR /app

# Copy layered JAR content (optimal layer caching order)
COPY --from=builder --chown=appuser:appgroup \
     /build/services/porting-service/target/dependency/dependencies/ ./
COPY --from=builder --chown=appuser:appgroup \
     /build/services/porting-service/target/dependency/spring-boot-loader/ ./
COPY --from=builder --chown=appuser:appgroup \
     /build/services/porting-service/target/dependency/snapshot-dependencies/ ./
COPY --from=builder --chown=appuser:appgroup \
     /build/services/porting-service/target/dependency/application/ ./

# Writable temp directories
RUN mkdir -p /tmp /app/logs && chown -R appuser:appgroup /tmp /app/logs

USER appuser

EXPOSE 8081

ENV JAVA_OPTS="-Xms256m -Xmx512m \
               -XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -Djava.security.egd=file:/dev/urandom \
               -XX:+ExitOnOutOfMemoryError \
               -Dspring.backgroundpreinitializer.ignore=true"

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8081/actuator/health || exit 1

# Use Spring Boot's layered JAR launcher
ENTRYPOINT ["sh", "-c", \
  "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
