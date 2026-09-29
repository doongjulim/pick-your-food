# 1. the frontend
FROM node:22-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 2. the jar, with the frontend served from static/
FROM eclipse-temurin:17-jdk AS backend
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
COPY --from=frontend /app/frontend/dist src/main/resources/static
RUN ./gradlew --no-daemon bootJar -x test

# 3. only the jar, run by a non-root user
FROM eclipse-temurin:17-jre
RUN useradd --system app
USER app
COPY --from=backend /app/build/libs/*-SNAPSHOT.jar /app.jar
# Render's free plan has 512MB
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
