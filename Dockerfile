FROM eclipse-temurin:21-jre

WORKDIR /app

ENV TZ=Asia/Shanghai \
    QUIZMATE_DATA=/data \
    QUIZMATE_HOST=0.0.0.0 \
    QUIZMATE_SECURE_COOKIE=true \
    SPRING_PROFILES_ACTIVE=prod

COPY target/quizmate-0.1.0.jar /app/app.jar

# /data 存数据库，/app/raw 存每个账号的资料和答案图片
VOLUME ["/data", "/app/raw"]
EXPOSE 8080

ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
