FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S forge && adduser -S forge -G forge && mkdir /data && chown forge:forge /data
WORKDIR /app
COPY app.jar app.jar
USER forge
ENV DATA_DIR=/data
EXPOSE 8080
VOLUME /data
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 CMD wget -q --spider http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]
