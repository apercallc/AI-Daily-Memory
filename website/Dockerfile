# syntax=docker/dockerfile:1
# Static marketing site (website/ is the Railway service root directory).
# nginx-unprivileged runs as the non-root nginx user; the template listens on
# the platform-injected PORT (Railway injects its own; 8080 is the local default).

FROM nginxinc/nginx-unprivileged:1.27-alpine
ENV PORT=8080
COPY index.html sentry-init.js site.js /usr/share/nginx/html/
COPY nginx-default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 8080
