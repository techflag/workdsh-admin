# 多阶段源码构建，供 docker-compose 使用。
# Admin.Dockerfile 仍是候选交付用的“预构建产物”镜像配方；本文件不替代它。
# 生产环境必须把三个基础镜像都固定为 digest（--build-arg ...=repo@sha256:...）。

ARG JAVA_BUILD_IMAGE=maven:3.9-eclipse-temurin-17
ARG NODE_IMAGE=node:22-alpine
ARG JAVA_IMAGE=eclipse-temurin:17-jre

FROM ${JAVA_BUILD_IMAGE} AS java-build
WORKDIR /build/server
# 先单独复制 pom，让依赖下载层在仅改源码时复用。
COPY server/pom.xml ./
RUN mvn -B -q dependency:go-offline || true
COPY server/src ./src
RUN mvn -B -DskipTests package

FROM ${NODE_IMAGE} AS web-build
WORKDIR /build/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM ${JAVA_IMAGE}
COPY --from=java-build /build/server/target/workdsh-admin-server-*.jar /opt/workdsh/server.jar
COPY --from=web-build /build/web/dist /opt/workdsh/static
ENV SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/opt/workdsh/static/ BIND_ADDRESS=0.0.0.0
# H2/SQLite 数据写入 /state/data；命名卷首次挂载时继承该目录的属主。
RUN mkdir -p /state/data && chown -R 10001:10001 /state
USER 10001:10001
WORKDIR /state
EXPOSE 18890
ENTRYPOINT ["java","-jar","/opt/workdsh/server.jar"]
