# Supply an approved immutable Java 17+ image using --build-arg JAVA_IMAGE=...@sha256:...
ARG JAVA_IMAGE
FROM ${JAVA_IMAGE}
COPY server/workdsh-admin-server.jar /opt/workdsh/server.jar
COPY web/dist /opt/workdsh/static
ENV SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/opt/workdsh/static/ BIND_ADDRESS=0.0.0.0
RUN mkdir -p /state && chown 10001:10001 /state
USER 10001:10001
WORKDIR /state
ENTRYPOINT ["java","-jar","/opt/workdsh/server.jar"]
