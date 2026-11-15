# Build context: frontend/ (docker build frontend). Both base images are pinned by tag and
# multi-arch digest; the tags are the newest at least 14 days old (spec 6.1), recorded in the
# progress report.
FROM node:26.13.0-alpine3.24@sha256:8996bb5821d62a8fd9bb27c1cb07462f3fdce6e47d640c6a881ead0b064ce216 AS build
WORKDIR /workspace
COPY package.json package-lock.json .npmrc ./
RUN npm ci
COPY index.html tsconfig.json tsconfig.app.json tsconfig.node.json vite.config.ts ./
COPY src src
# Tests run in CI, not here.
RUN npm run build

FROM nginx:1.30.5-alpine3.24@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94
# The image's entrypoint renders every file in /etc/nginx/templates/ into /etc/nginx/conf.d/ at start
# (envsubst over the container's environment), so the same image serves every environment.
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /workspace/dist /usr/share/nginx/html
# Non-root: the unprivileged user needs the rendered configuration, the cache and the pid file in
# places it can write, and a port above 1023 (the template listens on 8080).
RUN chown -R nginx:nginx /etc/nginx/conf.d /var/cache/nginx \
    && sed -i 's#^pid .*#pid /tmp/nginx.pid;#' /etc/nginx/nginx.conf
USER nginx
EXPOSE 8080
