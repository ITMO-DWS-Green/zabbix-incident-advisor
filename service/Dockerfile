FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew :app:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /opt/advisor
COPY --from=build /src/app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
