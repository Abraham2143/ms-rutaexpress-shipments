FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress clean package \
    && cp target/ms-rutaexpress-shipments-*.jar /build/application.jar

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN groupadd --gid 10001 shipments \
    && useradd --uid 10001 --gid shipments --no-create-home shipments \
    && mkdir -p /opt/oracle/wallet
COPY --from=build --chown=10001:10001 /build/application.jar /app/application.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
