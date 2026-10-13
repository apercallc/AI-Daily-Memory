# syntax=docker/dockerfile:1
# Static site plus the web app (website/ is the Railway service root directory).
# nginx-unprivileged runs as the non-root nginx user; the template listens on the
# platform-injected PORT (Railway injects its own; 8080 is the local default) and proxies /v1/ to
# the API service (see nginx-default.conf.template).

FROM nginxinc/nginx-unprivileged:1.27-alpine@sha256:65e3e85dbaed8ba248841d9d58a899b6197106c23cb0ff1a132b7bfe0547e4c0
ENV PORT=8080
# Railway private networking by default; override all four together for another setup.
ENV API_UPSTREAM=http://api.railway.internal:8080
ENV API_HOST=api.railway.internal
ENV NGINX_RESOLVER="[fd12::10] ipv6=on"
COPY index.html privacy.html terms.html sentry-init.js site.js /usr/share/nginx/html/
COPY app /usr/share/nginx/html/app
COPY nginx-default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 8080
