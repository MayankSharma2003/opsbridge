FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S opsbridge && adduser -S opsbridge -G opsbridge

COPY target/*.jar app.jar

RUN chown opsbridge:opsbridge app.jar

USER opsbridge

EXPOSE 8080

ENTRYPOINT ["java", "-Xms128m", "-Xmx400m", "-jar", "/app/app.jar"]
