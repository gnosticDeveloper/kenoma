FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY services/pom.xml services/
COPY services/common services/common
COPY services/bime services/bime
COPY services/vassago/pom.xml services/vassago/pom.xml
COPY services/raum/pom.xml services/raum/pom.xml
RUN mvn -pl services/common,services/bime -am clean install -DskipTests

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/services/bime/target/bime-*-exec.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
