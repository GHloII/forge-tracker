FROM node:22-alpine AS frontend
WORKDIR /ui
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-alpine AS backend
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
RUN chmod +x gradlew
COPY gradle.properties ./
RUN ./gradlew --no-daemon resolveDependencies
COPY src/ src/
COPY --from=frontend /ui/dist/ src/main/resources/static/
RUN ./gradlew --no-daemon bootJar -x frontendInstall -x frontendBuild

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S forge && adduser -S forge -G forge && mkdir /data && chown forge:forge /data
WORKDIR /app
COPY --from=backend /app/build/libs/forge-tracker-1.0.0.jar app.jar
USER forge
ENV DATA_DIR=/data JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
VOLUME /data
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD wget -q --spider http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
