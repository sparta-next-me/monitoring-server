FROM bellsoft/liberica-openjdk-alpine:21
# tzdata 설치 + KST 고정
RUN apk add --no-cache tzdata \
 && ln -snf /usr/share/zoneinfo/Asia/Seoul /etc/localtime \
 && echo "Asia/Seoul" > /etc/timezone

ENV TZ=Asia/Seoul
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar
EXPOSE 11118
ENTRYPOINT ["java", "-jar", "app.jar"]