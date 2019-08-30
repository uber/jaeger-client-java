FROM nginx:alpine

# Overwrite default configuration
ADD build/proxy.conf /etc/nginx/conf.d/default.conf
# Install generated certificates; run `gen.sh` first
ADD build/proxy.crt /etc/nginx/proxy.crt
ADD build/proxy.key /etc/nginx/proxy.key

EXPOSE 8080 14443

# Poll healthcheck port until it returns 2xx or 3xx.
CMD ["sh", "-c", "while ! { wget --spider -S http://jaeger-collector:14269; }; do echo waiting for healthcheck; sleep 1; done; nginx -g 'daemon off;'"]
