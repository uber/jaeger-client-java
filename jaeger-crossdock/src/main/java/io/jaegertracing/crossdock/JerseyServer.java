/*
 * Copyright (c) 2016, Uber Technologies, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.jaegertracing.crossdock;

import io.jaegertracing.Configuration;
import io.jaegertracing.Configuration.ReporterConfiguration;
import io.jaegertracing.Configuration.SamplerConfiguration;
import io.jaegertracing.crossdock.resources.behavior.EndToEndBehavior;
import io.jaegertracing.crossdock.resources.behavior.ExceptionMapper;
import io.jaegertracing.crossdock.resources.behavior.http.EndToEndBehaviorResource;
import io.jaegertracing.crossdock.resources.behavior.http.TraceBehaviorResource;
import io.jaegertracing.crossdock.resources.health.HealthResource;
import io.jaegertracing.internal.samplers.ConstSampler;
import io.jaegertracing.spi.Sender;
import io.jaegertracing.thrift.internal.senders.HttpSender;
import io.jaegertracing.thrift.internal.senders.UdpSender;
import io.opentracing.Tracer;
import io.opentracing.contrib.jaxrs2.client.ClientTracingFeature;
import io.opentracing.contrib.jaxrs2.server.ServerTracingDynamicFeature;
import io.opentracing.contrib.jaxrs2.server.SpanFinishingFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.servlet.DispatcherType;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import org.apache.log4j.BasicConfigurator;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.servlet.WebappContext;
import org.glassfish.jersey.filter.LoggingFilter;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

public class JerseyServer {
  private static final String SAMPLING_HOST_PORT = "SAMPLING_HOST_PORT";
  private static final String AGENT_HOST = "AGENT_HOST";
  private static final String COLLECTOR_HOST_PORT = "COLLECTOR_HOST_PORT";
  private static final String COLLECTOR_HOST_PIN = "COLLECTOR_HOST_PIN";

  // TODO should not be static, should be final
  public static Client client;

  private final HttpServer server;

  private final Configuration config;

  public JerseyServer(String host, int port, Configuration configuration, List<Object> resources)
      throws IOException {
    this.config = configuration;

    // create a resource config that scans for JAX-RS resources and providers
    final ResourceConfig rc = new ResourceConfig();

    resources.forEach(rc::register);
    rc.register(new ServerTracingDynamicFeature.Builder(config.getTracer())
          .withTraceSerialization(false)
          .build())
        .register(LoggingFilter.class)
        .register(ExceptionMapper.class)
        .register(JacksonFeature.class);

    WebappContext context = new WebappContext("grizzly web context", "");
    context.addServlet("jaxrs", new ServletContainer(rc))
        .addMapping("/", "/*");
    context.addFilter("tracingFilter", new SpanFinishingFilter(configuration.getTracer()))
        .addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), "/*");

    server = HttpServer.createSimpleServer(".", host, port);
    context.deploy(server);
    client = initializeClient(config);
    server.start();
  }

  public void addNetworkListener(NetworkListener networkListener) {
    server.addListener(networkListener);
  }

  private static Client initializeClient(final Configuration config) {
    return ClientBuilder.newClient()
        .register(ExceptionMapper.class)
        .register(new ClientTracingFeature.Builder(config.getTracer()).build())
        .register(JacksonFeature.class);
  }

  public void shutdown() throws ExecutionException, InterruptedException {
    server.shutdownNow();
    Iterator<NetworkListener> iterator = server.getListeners().iterator();
    while (iterator.hasNext()) {
      NetworkListener next = iterator.next();
      GrizzlyFuture<NetworkListener> shutdown = next.shutdown();
    }
  }

  public Tracer getTracer() {
    return config.getTracer();
  }

  public static void main(String[] args) throws Exception {
    BasicConfigurator.configure();

    String serviceName = serviceNameFromEnv();
    Configuration configuration = new Configuration(serviceName)
        .withSampler(new SamplerConfiguration().withType(ConstSampler.TYPE).withParam(0))
        .withReporter(new ReporterConfiguration().withLogSpans(true));

    JerseyServer server = new JerseyServer("0.0.0.0", 8081, configuration,
        Arrays.asList(new TraceBehaviorResource(configuration.getTracer()),
            new EndToEndBehaviorResource(new EndToEndBehavior(getEvn(SAMPLING_HOST_PORT, "jaeger-agent:5778"),
                "crossdock-" + serviceName,
                senderFromEnv(getEvn(COLLECTOR_HOST_PORT, "jaeger-collector:14268"),
                    getEvn(COLLECTOR_HOST_PORT, "jaeger-collector-https-proxy:14443"),
                    getEvn(COLLECTOR_HOST_PIN, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
                    getEvn(AGENT_HOST, "jaeger-agent")))),
            new HealthResource()));

    server.addNetworkListener(new NetworkListener("health", "0.0.0.0", 8080));

    Thread.currentThread().join();
  }

  private static String getEvn(String envName, String defaultValue) {
    String env = System.getenv(envName);
    if (env == null) {
      return defaultValue;
    }
    return env;
  }

  private static Sender senderFromEnv(String collectorHostPort,
      String collectorHttpsHostPort, String collectorHttpsPin, String agentHost) {
    String senderEnvVar = System.getenv(Constants.ENV_PROP_SENDER_TYPE);
    if ("http".equalsIgnoreCase(senderEnvVar)) {
      return new HttpSender.Builder(String.format("http://%s/api/traces", collectorHostPort))
          .build();
    } else if ("https".equalsIgnoreCase(senderEnvVar)) {
      return new HttpSender.Builder(String.format("https://%s/api/traces", collectorHttpsHostPort))
          .withCertificatePinning(new String[] {collectorHttpsPin})
          .acceptSelfSigned()
          .build();
    } else if ("udp".equalsIgnoreCase(senderEnvVar) || senderEnvVar == null || senderEnvVar.isEmpty()) {
      return new UdpSender(agentHost, 0, 0);
    }

    throw new IllegalStateException("Env variable " + Constants.ENV_PROP_SENDER_TYPE
        + ", is not valid, choose 'udp', 'http' or 'https'");
  }

  private static String serviceNameFromEnv() {
    String senderEnvVar = System.getenv(Constants.ENV_PROP_SENDER_TYPE);
    if (senderEnvVar == null || senderEnvVar.isEmpty()) {
      return "java";
    }
    return "java-" + senderEnvVar;
  }
}
