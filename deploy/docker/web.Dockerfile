# Production image for the SPA, served by Caddy. Build context: frontend/ (see docker-compose.prod.yml).
# The Caddyfile is mounted at run time from deploy/compose/Caddyfile.
FROM node:22.23.2-alpine AS build
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
WORKDIR /src
COPY . .
RUN corepack enable \
 && pnpm install --frozen-lockfile \
 && pnpm build

FROM caddy:2.11.9-alpine
COPY --from=build /src/dist /srv
# Caddy runs as a fixed non-root uid. Port 80/443 need no capability: compose sets
# net.ipv4.ip_unprivileged_port_start=0 for this container.
RUN mkdir -p /data /config && chown -R 10001:10001 /data /config
USER 10001:10001
