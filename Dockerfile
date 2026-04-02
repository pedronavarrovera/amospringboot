# This Dockerfile builds and runs a Java app,a Spring Boot app, using a multi-stage build. 
# That means it uses one image to compile the application and another, smaller image to run it
#
# Option 1 (recommended): Container images (full control, Java 23 OK)
# Build (Java 23)
#
RUN echo "USING-DOCKERFILE-2026-04-03" 
#
FROM maven:3.9-eclipse-temurin-23 AS build
# Docker image that already includes Maven 3.9 and Eclipse Temurin JDK 23
# AS build gives this stage the name build, so it can be referenced later
WORKDIR /app
# Sets /app as the working directory inside the container. All next commands run from there
# Copies only the pom.xml file into the container first
# This is done separately for efficiency: if only your source code changes but pom.xml stays the same
# can reuse the cached dependency layer
COPY pom.xml .
#
# This downloads the Maven dependencies in advance
RUN mvn -q -DskipTests dependency:go-offline
# Copies only the pom.xml file into the container first
# This is done separately for efficiency: if only your source code changes but pom.xml stays the same
# can reuse the cached dependency layer
COPY . .
# Copies the rest of the project files into the container, including source code and resources
RUN mvn -q -DskipTests package
# Builds the application this creates a JAR file in target/, such as target/myapp-0.0.1-SNAPSHOT.jar
# Run (JRE 23)
# Instead of using the full Maven + JDK image, it uses a lighter image containing only the Java Runtime
# That makes the final image:smaller, cleaner, more secure
FROM eclipse-temurin:23-jre
WORKDIR /app
COPY --from=build /app/target/amospringboot-0.0.1-SNAPSHOT.jar app.jar
# Copies the built JAR from the build stage into the runtime image
# So the final container only contains the runnable JAR, not the source code or Maven cache
ENV PORT=8080 JAVA_OPTS=""
EXPOSE 8080
# Documents that the container listens on port 8080. This does not publish the port automatically. It is mainly metadata for humans and tools
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
# This is the command that starts the application when the container runs