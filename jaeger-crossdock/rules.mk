PROJECT=jaeger-crossdock
XDOCK_YAML=$(PROJECT)/docker-compose.yml

JAEGER_COMPOSE_URL=https://raw.githubusercontent.com/jaegertracing/jaeger/master/crossdock/jaeger-docker-compose.yml
XDOCK_JAEGER_YAML=$(PROJECT)/jaeger-docker-compose.yml

.PHONY: crossdock
crossdock: gradle-compile crossdock-proxy-secret-gen crossdock-download-jaeger
	docker-compose -f $(XDOCK_YAML) -f $(XDOCK_JAEGER_YAML) kill java-udp java-http java-https
	docker-compose -f $(XDOCK_YAML) -f $(XDOCK_JAEGER_YAML) rm -f java-udp java-http java-https
	docker-compose -f $(XDOCK_YAML) -f $(XDOCK_JAEGER_YAML) build java-udp java-http java-https
	docker-compose -f $(XDOCK_YAML) -f $(XDOCK_JAEGER_YAML) run crossdock

.PHONY: crossdock-fresh
crossdock-fresh: gradle-compile crossdock-proxy-secret-gen crossdock-download-jaeger
	docker-compose -f $(XDOCK_YAML) -f $(XDOCK_JAEGER_YAML) down --rmi all
	docker-compose -f $(XDOCK_YAML) -f $(XDOCK_JAEGER_YAML) run crossdock

gradle-compile:
	./gradlew :jaeger-crossdock:shadowJar

.PHONY: crossdock-logs crossdock-download-jaeger
crossdock-logs:
	docker-compose -f $(XDOCK_YAML) -f $(XDOCK_JAEGER_YAML) logs

.PHONY: crossdock-clean crossdock-download-jaeger
crossdock-clean:
	docker-compose -f $(XDOCK_YAML) -f $(XDOCK_JAEGER_YAML)  down

.PHONY: crossdock-download-jaeger
crossdock-download-jaeger:
	curl -o $(XDOCK_JAEGER_YAML) $(JAEGER_COMPOSE_URL)

.PHONY: crossdock-proxy-secret-gen
crossdock-proxy-secret-gen:
	$(PROJECT)/https-proxy/gen.sh jaeger-collector-https-proxy jaeger-collector:14268
