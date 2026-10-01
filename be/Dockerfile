# Build with the reviewed Gradle wrapper; external service tests run separately.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle/wrapper/ ./gradle/wrapper/
COPY src/main/ ./src/main/
# The repository intentionally stores gradlew without an executable file mode.
RUN bash ./gradlew bootJar --no-daemon --console=plain --max-workers=2

# Only Java 21 JRE and the packaged application enter the runtime image.
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/build/libs/be-0.0.1-SNAPSHOT.jar /app/app.jar
# Initial runtime bounds, not a measured optimum; never apply these to Gradle.
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Seoul"
USER 10001:10001
# PORT and demo,render settings are supplied at runtime, never during image build.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
