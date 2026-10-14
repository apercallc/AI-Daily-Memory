# syntax=docker/dockerfile:1
# Static site plus the web app (website/ is the Railway service root directory).
# nginx-unprivileged runs as the non-root nginx user; the template listens on the
# platform-injected PORT (Railway injects its own; 8080 is the local default) and proxies /v1/ to
# the API service (see nginx-default.conf.template).

FROM nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6
ENV PORT=8080
# Railway private networking by default; override all four together for another setup.
ENV API_UPSTREAM=http://api.railway.internal:8080
ENV API_HOST=api.railway.internal
ENV NGINX_RESOLVER="[fd12::10] ipv6=on"
COPY index.html privacy.html terms.html sentry-init.js site.js /usr/share/nginx/html/
COPY app /usr/share/nginx/html/app
COPY nginx-default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 8080
