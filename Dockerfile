FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY build/libs/openmrs-cce-receiver-adaptor-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
