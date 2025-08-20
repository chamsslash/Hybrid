
FROM nginx:1.21-alpine
RUN rm /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/nginx.conf
COPY std-settings.conf /etc/nginx/common_proxy_settings.conf