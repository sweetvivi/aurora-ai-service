# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

COPY src/ src/
RUN --mount=type=cache,id=hub-maven-repository,target=/root/.m2/repository \
    mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-jammy

# ffmpeg 是画布「视频编辑器」导出/下载裁剪结果的硬依赖：缺它时 /videos/trim 会以
# 503 失败，前端只能表现为按钮没有产出。装在镜像里，而不是要求部署方自己配。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl fontconfig fonts-dejavu-core ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app

# Matches the repackaged Spring Boot jar whatever the project version is: pinning `*SNAPSHOT.jar`
# silently produced an image without the jar once the version was bumped to 0.6.0 (COPY with a
# wildcard that matches nothing does not fail the build), and the container then died at start with
# "Unable to access jarfile /app/app.jar". `*.jar` does not match spring-boot's `.jar.original`.
COPY --from=build /workspace/target/backend-service-*.jar /app/app.jar

# Fail the build, not the deployment, if the jar did not make it into the image.
RUN test -s /app/app.jar || (echo "ERROR: /app/app.jar missing — check the COPY pattern above" && exit 1)

RUN mkdir -p /app/data/uploads \
    && chown -R app:app /app

USER app

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=8 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
