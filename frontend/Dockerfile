# Build context: frontend/ (docker build frontend). Both base images are pinned by tag and
# multi-arch digest; the tags are the newest at least 14 days old (spec 6.1), recorded in the
# progress report.
FROM node:26.13.0-alpine3.24@sha256:8996bb5821d62a8fd9bb27c1cb07462f3fdce6e47d640c6a881ead0b064ce216 AS build
WORKDIR /workspace
COPY package.json package-lock.json .npmrc ./
RUN npm ci
# The build's inputs, named one by one on purpose: public/ (the dev server's config.json) stays out,
# so Vite cannot copy it into the bundle; nginx renders the image's /config.json. A new root input
# of the build has to be added here.
COPY index.html tsconfig.json tsconfig.app.json tsconfig.node.json vite.config.ts ./
COPY src src
# Tests run in CI, not here.
RUN npm run build

FROM nginx:1.30.5-alpine3.24@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94
# The image's entrypoint renders every file in /etc/nginx/templates/ into /etc/nginx/conf.d/ at start
# (envsubst over the container's environment), so the same image serves every environment.
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
# The image's 15-local-resolvers.envsh then exports NGINX_LOCAL_RESOLVERS, the nameservers of
# /etc/resolv.conf, for the template's resolver.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
COPY --chmod=755 nginx/10-validate-config.sh /docker-entrypoint.d/10-validate-config.sh
COPY --from=build /workspace/dist /usr/share/nginx/html
# Non-root: the unprivileged user needs the rendered configuration, the cache and the pid file in
# places it can write, and a port above 1023 (the template listens on 8080). The `user` directive
# means nothing then, and nginx would warn about it at every start.
RUN chown -R nginx:nginx /etc/nginx/conf.d /var/cache/nginx \
    && sed -i -e 's#^pid .*#pid /tmp/nginx.pid;#' -e '/^user /d' /etc/nginx/nginx.conf
USER nginx
EXPOSE 8080
