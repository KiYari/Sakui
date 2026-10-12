# syntax=docker/dockerfile:1
#
# Build context is ./web.

# ---------- build ----------
FROM node:22-alpine AS build
WORKDIR /app

# How this build reaches chat servers: `same-origin` (default; the bundled
# server behind this nginx), `relay` (the user enters a server's IPv4 address
# and nginx forwards to it) or `direct` (the browser connects itself; for hosting
# the client elsewhere). Baked into the bundle, so it is a build argument.
ARG EECK_SERVER_MODE=same-origin
ENV VITE_EECK_SERVER_MODE=$EECK_SERVER_MODE

# Lockfile-only install layer: npm ci reruns only when dependencies actually change.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# `npm run build` = tsc -b && vite build — a type error fails the image build,
# which is deliberate: a broken bundle should never reach a server.
RUN npm run build

# ---------- runtime ----------
FROM nginx:1.27-alpine AS runtime
ARG EECK_SERVER_MODE=same-origin

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY relay /tmp/relay
# Relay mode installs the forwarding rules; every other mode gets an empty include.
RUN if [ "$EECK_SERVER_MODE" = "relay" ]; then \
      mv /tmp/relay/relay.conf /etc/nginx/snippets/relay.conf && \
      mv /tmp/relay/relay-maps.conf /etc/nginx/conf.d/relay-maps.conf; \
    else \
      : > /etc/nginx/snippets/relay.conf; \
    fi && rm -rf /tmp/relay

COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q --spider http://127.0.0.1/ || exit 1
