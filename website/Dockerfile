# syntax=docker/dockerfile:1
# Static marketing site (website/ is the Railway service root directory).
# nginx-unprivileged runs as the non-root nginx user; the template listens on
# the platform-injected PORT (8080 on Railway, overridable locally).

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY index.html /usr/share/nginx/html/index.html
COPY nginx-default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 8000
