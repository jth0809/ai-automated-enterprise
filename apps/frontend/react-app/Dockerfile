# syntax=docker/dockerfile:1

# ---------- Build stage ----------
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---------- Runtime stage ----------
# Unprivileged nginx: listens on 8080, temp/pid under /tmp — matches the
# Deployment's readOnlyRootFilesystem + emptyDir mounts.
FROM nginxinc/nginx-unprivileged:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

# Numeric UID so the K8s runAsNonRoot check can verify it.
USER 101
EXPOSE 8080
