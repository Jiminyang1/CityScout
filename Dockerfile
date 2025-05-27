# 构建阶段
FROM maven:3.8-openjdk-8 AS builder
WORKDIR /build
COPY . .
RUN mvn clean package -DskipTests

# 运行阶段
FROM openjdk:8-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"] 