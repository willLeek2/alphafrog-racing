FROM eclipse-temurin:17-jdk-noble
COPY target/racing-server-*.jar /app.jar
EXPOSE 50071
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app.jar"]
