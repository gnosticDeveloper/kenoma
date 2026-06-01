FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY services/pom.xml services/
COPY services/common services/common
COPY services/vassago services/vassago
COPY services/raum/pom.xml services/raum/pom.xml
RUN mvn -pl services/common,services/vassago -am clean install -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/services/vassago/target/vassago-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]