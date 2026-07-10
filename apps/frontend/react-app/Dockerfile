# syntax=docker/dockerfile:1

# ---------- Build stage ----------
FROM node:26-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---------- Runtime stage ----------
# Unprivileged nginx: listens on 8080, temp/pid under /tmp — matches the
# Deployment's readOnlyRootFilesystem + emptyDir mounts.
FROM nginxinc/nginx-unprivileged:alpine

# The upstream weekly image can briefly lag newly patched Alpine packages.
# CVE-2026-33630 is fixed in c-ares 1.34.8-r0; upgrade only that library and
# fail the build if the fixed version is unavailable instead of suppressing
# the Trivy HIGH finding.
USER root
RUN apk add --no-cache 'c-ares>=1.34.8-r0'

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

# Numeric UID so the K8s runAsNonRoot check can verify it.
USER 101
EXPOSE 8080
