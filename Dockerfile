# ============================================================
# Spring Boot Agent — 复制预构建 JAR
# 要求: 先执行 mvn package -DskipTests 生成 target/*.jar
# ============================================================

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY target/db-diagnostic-agent-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
