FROM maven:3.8-jdk-8 AS build

WORKDIR /src
COPY postgres-indexed-java8-implementation/pom.xml ./
RUN mvn -B -q -DskipTests package && rm -rf target && mvn -B -q dependency:go-offline
COPY postgres-indexed-java8-implementation/src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:8-jre-jammy
WORKDIR /benchmark
COPY --from=build /src/target/firewall-postgres-java8.jar /benchmark/firewall-postgres-java8.jar
COPY postgres-indexed-java8-implementation/run-batch /benchmark/run-batch
RUN chmod 0755 /benchmark/firewall-postgres-java8.jar /benchmark/run-batch

ENV MASTER_DATABASE_URL="jdbc:postgresql://db:5432/firewall?user=firewall&password=firewall&sslmode=disable"

EXPOSE 8080
HEALTHCHECK --interval=2s --timeout=2s --start-period=10s --retries=600 \
  CMD ["java", "-jar", "/benchmark/firewall-postgres-java8.jar", "health", "--server", "http://127.0.0.1:8080"]
ENTRYPOINT ["java", "-jar", "/benchmark/firewall-postgres-java8.jar"]
CMD ["serve", "--data", "/benchmark/data", "--listen", ":8080"]
