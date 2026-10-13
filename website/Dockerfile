# syntax=docker/dockerfile:1
# Static marketing site (website/ is the Railway service root directory).
# nginx-unprivileged runs as the non-root nginx user; the template listens on
# the platform-injected PORT (Railway injects its own; 8080 is the local default).

FROM nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6
ENV PORT=8080
COPY index.html sentry-init.js site.js /usr/share/nginx/html/
COPY nginx-default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 8080
