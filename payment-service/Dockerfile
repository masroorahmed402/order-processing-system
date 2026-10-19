# Runtime image only: build the jar first with `mvn -DskipTests package`.
FROM eclipse-temurin:21.0.9_10-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY target/payment-service-*-exec.jar app.jar
USER app
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
