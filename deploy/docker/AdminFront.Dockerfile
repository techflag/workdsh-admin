# Optional local acceptance front; provide an approved immutable Node 22+ image.
ARG NODE_IMAGE
FROM ${NODE_IMAGE}
COPY deploy/docker/admin-loopback-front.mjs /opt/front.mjs
USER 10001:10001
ENTRYPOINT ["node","/opt/front.mjs"]
