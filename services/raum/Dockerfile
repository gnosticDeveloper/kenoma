FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY services/pom.xml services/
COPY services/common services/common
COPY services/raum services/raum
COPY services/vassago/pom.xml services/vassago/pom.xml
COPY services/bime/pom.xml services/bime/pom.xml
RUN mvn -pl services/common,services/raum -am clean install -DskipTests

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl postgresql-client && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/services/raum/target/raum-*-exec.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]