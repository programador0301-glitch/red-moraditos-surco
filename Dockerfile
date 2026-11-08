# ---- Etapa 1: compilar con Maven ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# primero el pom para cachear dependencias
COPY pom.xml .
RUN mvn -q -e -B dependency:go-offline
# luego el código y se empaqueta el jar
COPY src ./src
RUN mvn -q -e -B clean package

# ---- Etapa 2: imagen liviana solo para correr ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/red-surco-web.jar app.jar
# Render inyecta el puerto en la variable PORT
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
