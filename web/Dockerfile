# syntax=docker/dockerfile:1
#
# Build context is ./web.

# ---------- build ----------
FROM node:22-alpine AS build
WORKDIR /app

# Lockfile-only install layer: npm ci reruns only when dependencies actually change.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# `npm run build` = tsc -b && vite build — a type error fails the image build,
# which is deliberate: a broken bundle should never reach a server.
RUN npm run build

# ---------- runtime ----------
FROM nginx:1.27-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q --spider http://127.0.0.1/ || exit 1
