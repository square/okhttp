
FROM openjdk:17-jdk-slim 

WORKDIR /app 

COPY okhttp/build/libs/okhttp-*.jar app.jar 

ENTRYPOINT ["java", "-jar", "app.jar"] 

