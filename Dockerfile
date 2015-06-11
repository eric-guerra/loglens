FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /src/target/loglens-1.0.0.jar /app/loglens.jar
EXPOSE 9100
ENTRYPOINT ["java", "-XX:+UseG1GC", "-jar", "/app/loglens.jar"]
