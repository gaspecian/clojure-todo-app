# ---- Build stage ----
FROM clojure:temurin-21-tools-deps AS builder
WORKDIR /app

# Cache deps before copying source
COPY deps.edn build.clj ./
RUN clj -P

COPY src src
COPY resources resources
RUN clj -T:build uber

# ---- Run stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /app/target/todo-api.jar app.jar
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:3000/health || exit 1
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
