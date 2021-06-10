/*
 * Copyright (c) 2018, The Jaeger Authors
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

package io.jaegertracing.internal;

import io.jaegertracing.internal.baggage.BaggageSetter;
import io.jaegertracing.internal.baggage.DefaultBaggageRestrictionManager;
import io.jaegertracing.internal.clock.Clock;
import io.jaegertracing.internal.clock.SystemClock;
import io.jaegertracing.internal.exceptions.EmptyIpException;
import io.jaegertracing.internal.exceptions.NotFourOctetsException;
import io.jaegertracing.internal.exceptions.UnsupportedFormatException;
import io.jaegertracing.internal.metrics.Metrics;
import io.jaegertracing.internal.metrics.NoopMetricsFactory;
import io.jaegertracing.internal.propagation.BinaryCodec;
import io.jaegertracing.internal.propagation.TextMapCodec;
import io.jaegertracing.internal.reporters.RemoteReporter;
import io.jaegertracing.internal.samplers.RemoteControlledSampler;
import io.jaegertracing.internal.samplers.SamplingStatus;
import io.jaegertracing.internal.utils.Utils;
import io.jaegertracing.spi.BaggageRestrictionManager;
import io.jaegertracing.spi.Extractor;
import io.jaegertracing.spi.Injector;
import io.jaegertracing.spi.MetricsFactory;
import io.jaegertracing.spi.Reporter;
import io.jaegertracing.spi.Sampler;
import io.opentracing.References;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tag;
import io.opentracing.tag.Tags;
import io.opentracing.util.ThreadLocalScopeManager;
import java.io.Closeable;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@ToString
@Slf4j
public class JaegerTracer implements Tracer, Closeable {

  private final String version;
  private final String serviceName;
  private final Reporter reporter;
  private final Sampler sampler;
  private final Map<String, ?> tags;
  private final boolean zipkinSharedRpcSpan;
  private final boolean expandExceptionLogs;
  private final boolean useTraceId128Bit;

  @ToString.Exclude
  private final PropagationRegistry registry;
  @ToString.Exclude
  private final Clock clock;
  @ToString.Exclude
  private final Metrics metrics;
  @ToString.Exclude
  private final ScopeManager scopeManager;
  @ToString.Exclude
  private final BaggageSetter baggageSetter;
  @ToString.Exclude
  private final JaegerObjectFactory objectFactory;
  @ToString.Exclude
  private final int ipv4; // human readable representation is present within the tag map
  @ToString.Exclude
  private Thread shutdownHook;

  protected JaegerTracer(JaegerTracer.Builder builder) {
    this.serviceName = builder.serviceName;
    this.reporter = builder.reporter;
    this.sampler = builder.sampler;
    this.registry = builder.registry;
    this.clock = builder.clock;
    this.metrics = builder.metrics;
    this.zipkinSharedRpcSpan = builder.zipkinSharedRpcSpan;
    this.scopeManager = builder.scopeManager;
    this.baggageSetter = new BaggageSetter(builder.baggageRestrictionManager, metrics);
    this.expandExceptionLogs = builder.expandExceptionLogs;
    this.objectFactory = builder.objectFactory;
    this.useTraceId128Bit = builder.useTraceId128Bit;

    this.version = loadVersion();

    Map<String, Object> tags = new HashMap<String, Object>(builder.tags);
    tags.put(Constants.JAEGER_CLIENT_VERSION_TAG_KEY, this.version);
    if (tags.get(Constants.TRACER_HOSTNAME_TAG_KEY) == null) {
      String hostname = getHostName();
      if (hostname != null) {
        tags.put(Constants.TRACER_HOSTNAME_TAG_KEY, hostname);
      }
    }
    int ipv4;
    Object ipTag = tags.get(Constants.TRACER_IP_TAG_KEY);
    if (ipTag == null) {
      try {
        tags.put(Constants.TRACER_IP_TAG_KEY, InetAddress.getLocalHost().getHostAddress());
        ipv4 = Utils.ipToInt(Inet4Address.getLocalHost().getHostAddress());
      } catch (UnknownHostException e) {
        ipv4 = 0;
      }
    } else {
      try {
        ipv4 = Utils.ipToInt(ipTag.toString());
      } catch (EmptyIpException e) {
        ipv4 = 0;
      } catch (NotFourOctetsException e) {
        ipv4 = 0;
      }
    }
    this.ipv4 = ipv4;
    this.tags = Collections.unmodifiableMap(tags);

    if (builder.manualShutdown || runsInGlassFish()) {
      log.info("No shutdown hook registered: Please call close() manually on application shutdown.");
    } else {
      // register this tracer with a shutdown hook, to flush the spans before the VM shuts down
      shutdownHook = new Thread() {
        @Override
        public void run() {
          shutdownHook = null;
          JaegerTracer.this.close();
        }
      };
      Runtime.getRuntime().addShutdownHook(shutdownHook);
    }
  }

  private boolean runsInGlassFish() {
    return System.getProperty("com.sun.aas.instanceRoot") != null;
  }

  public String getVersion() {
    return version;
  }

  public Metrics getMetrics() {
    return metrics;
  }

  public String getServiceName() {
    return serviceName;
  }

  public Map<String, ?> tags() {
    return tags;
  }

  public int getIpv4() {
    return ipv4;
  }

  Clock clock() {
    return clock;
  }

  Reporter getReporter() {
    return reporter;
  }

  void reportSpan(JaegerSpan span) {
    reporter.report(span);
    metrics.spansFinished.inc(1);
  }

  @Override
  public ScopeManager scopeManager() {
    return scopeManager;
  }

  /**
   * Retrieves the currently active span from the {@link ScopeManager}. It cannot be guaranteed that this span
   * will be a {@link JaegerSpan}, as other libraries might have set this active span there. Consumers expecting
   * this to return a {@link JaegerSpan} should always check the type of the return and act accordingly.
   *
   * @return the currently active span from the {@link ScopeManager}
   */
  @Override
  public Span activeSpan() {
    // the active scope might have been added there through an API extension, similar to what the OT java-metrics
    // library does -- therefore, we can't guarantee that we are returning a JaegerSpan here.
    return this.scopeManager.activeSpan();
  }

  @Override
  public JaegerTracer.SpanBuilder buildSpan(String operationName) {
    return objectFactory.createSpanBuilder(this, operationName);
  }

  @Override
  public <T> void inject(SpanContext spanContext, Format<T> format, T carrier) {
    Injector<T> injector = registry.getInjector(format);
    if (injector == null) {
      throw new UnsupportedFormatException(format);
    }
    injector.inject((JaegerSpanContext) spanContext, carrier);
  }

  @Override
  public <T> JaegerSpanContext extract(Format<T> format, T carrier) {
    Extractor<T> extractor = registry.getExtractor(format);
    if (extractor == null) {
      throw new UnsupportedFormatException(format);
    }
    return extractor.extract(carrier);
  }

  /**
   * Shuts down the {@link Reporter} and {@link Sampler}
   */
  @Override
  public void close() {
    reporter.close();
    sampler.close();
    if (shutdownHook != null) {
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
    }
  }

  public class SpanBuilder implements Tracer.SpanBuilder {

    private static final long MIN_EPOCH_MICROSECONDS = 1000000000000000L;

    private String operationName;
    private long startTimeMicroseconds;
    /**
     * In 99% situations there is only one parent (childOf), so we do not want to allocate
     * a collection of references.
     */
    private List<Reference> references = Collections.emptyList();

    private final Map<String, Object> tags = new HashMap<String, Object>();
    private boolean ignoreActiveSpan = false;

    protected SpanBuilder(String operationName) {
      this.operationName = operationName;
    }

    private void verifyStartTimeInMicroseconds() {
      if (startTimeMicroseconds < MIN_EPOCH_MICROSECONDS) {
        log.warn("'startTimeMicroseconds' {} is not a valid epoch microseconds timestamp", startTimeMicroseconds);
      }
    }

    @Override
    public JaegerTracer.SpanBuilder asChildOf(SpanContext parent) {
      return addReference(References.CHILD_OF, parent);
    }

    @Override
    public JaegerTracer.SpanBuilder asChildOf(Span parent) {
      return addReference(References.CHILD_OF, parent != null ? parent.context() : null);
    }

    @Override
    public JaegerTracer.SpanBuilder addReference(String referenceType, SpanContext reference) {
      if (reference == null) {
        return this;
      }
      if (!(reference instanceof JaegerSpanContext)) {
        log.warn("Expected to have a JaegerSpanContext but got " + reference.getClass().getName());
        return this;
      }

      JaegerSpanContext referencedContext = (JaegerSpanContext) reference;

      // Jaeger thrift currently does not support other reference types
      if (!References.CHILD_OF.equals(referenceType)
          && !References.FOLLOWS_FROM.equals(referenceType)) {
        return this;
      }

      if (references.isEmpty()) {
        // Optimization for 99% situations, when there is only one parent
        references = Collections.singletonList(new Reference(referencedContext, referenceType));
      } else {
        if (references.size() == 1) {
          references = new ArrayList<Reference>(references);
        }
        references.add(new Reference(referencedContext, referenceType));
      }

      return this;
    }

    @Override
    public JaegerTracer.SpanBuilder withTag(String key, String value) {
      tags.put(key, value);
      return this;
    }

    @Override
    public JaegerTracer.SpanBuilder withTag(String key, boolean value) {
      tags.put(key, value);
      return this;
    }

    @Override
    public JaegerTracer.SpanBuilder withTag(String key, Number value) {
      tags.put(key, value);
      return this;
    }

    @Override
    public <T> Tracer.SpanBuilder withTag(Tag<T> tag, T value) {
      if (tag != null && tag.getKey() != null) {
        this.tags.put(tag.getKey(), value);
      }
      return this;
    }

    @Override
    public JaegerTracer.SpanBuilder withStartTimestamp(long microseconds) {
      this.startTimeMicroseconds = microseconds;
      return this;
    }

    private JaegerSpanContext createNewContext() {
      String debugId = getDebugId();
      long spanId = Utils.uniqueId();
      long traceIdLow = spanId;
      long traceIdHigh = isUseTraceId128Bit() ? Utils.uniqueId() : 0;

      byte flags = 0;
      if (debugId != null) {
        flags = (byte) (flags | JaegerSpanContext.flagSampled | JaegerSpanContext.flagDebug);
        tags.put(Constants.DEBUG_ID_HEADER_KEY, debugId);
      } else {
        // TODO: (prithvi) Don't assume operationName is set on creation
        SamplingStatus samplingStatus = sampler.sample(operationName, spanId);
        if (samplingStatus.isSampled()) {
          flags |= JaegerSpanContext.flagSampled;
          tags.putAll(samplingStatus.getTags());
        }
      }

      return getObjectFactory().createSpanContext(
          traceIdHigh,
          traceIdLow,
          spanId,
          0,
          flags,
          getBaggage(),
          debugId);
    }

    private Map<String, String> getBaggage() {
      Map<String, String> baggage = null;

      // optimization for 99% use cases, when there is only one parent
      if (references.size() == 1) {
        return references.get(0).getSpanContext().baggage();
      }

      for (Reference reference : references) {
        if (reference.getSpanContext().baggage() != null) {
          if (baggage == null) {
            baggage = new HashMap<String, String>();
          }
          baggage.putAll(reference.getSpanContext().baggage());
        }
      }

      return baggage;
    }

    private JaegerSpanContext createChildContext() {
      JaegerSpanContext preferredReference = preferredReference();

      if (isRpcServer()) {
        if (isSampled()) {
          metrics.tracesJoinedSampled.inc(1);
        } else {
          metrics.tracesJoinedNotSampled.inc(1);
        }

        // Zipkin server compatibility
        if (zipkinSharedRpcSpan) {
          return preferredReference;
        }
      }

      return getObjectFactory().createSpanContext(
          preferredReference.getTraceIdHigh(),
          preferredReference.getTraceIdLow(),
          Utils.uniqueId(),
          preferredReference.getSpanId(),
          // should we do OR across passed references?
          preferredReference.getFlags(),
          getBaggage(),
          null);
    }

    //Visible for testing
    boolean isRpcServer() {
      return Tags.SPAN_KIND_SERVER.equals(tags.get(Tags.SPAN_KIND.getKey()));
    }

    private JaegerSpanContext preferredReference() {
      Reference preferredReference = references.get(0);
      for (Reference reference : references) {
        // childOf takes precedence as a preferred parent
        if (References.CHILD_OF.equals(reference.getType())
            && !References.CHILD_OF.equals(preferredReference.getType())) {
          preferredReference = reference;
          break;
        }
      }
      return preferredReference.getSpanContext();
    }

    private boolean isSampled() {
      if (references != null) {
        for (Reference reference : references) {
          if (reference.getSpanContext().isSampled()) {
            return true;
          }
        }
      }
      return false;
    }

    private String getDebugId() {
      if (references.isEmpty()) {
        return null;
      }
      return references.get(0).getSpanContext().getDebugId();
    }

    @Override
    public JaegerSpan start() {
      JaegerSpanContext context;

      // Check if active span should be established as CHILD_OF relationship
      if (references.isEmpty() && !ignoreActiveSpan && null != scopeManager.activeSpan()) {
        asChildOf(scopeManager.activeSpan());
      }

      boolean isNewContext = references.isEmpty() || !references.get(0).getSpanContext().hasTrace();
      if (isNewContext) {
        context = createNewContext();
      } else {
        context = createChildContext();
      }

      long startTimeNanoTicks = 0;
      boolean computeDurationViaNanoTicks = false;

      if (startTimeMicroseconds == 0) {
        startTimeMicroseconds = clock.currentTimeMicros();
        if (!clock.isMicrosAccurate()) {
          startTimeNanoTicks = clock.currentNanoTicks();
          computeDurationViaNanoTicks = true;
        }
      } else {
        verifyStartTimeInMicroseconds();
      }

      JaegerSpan jaegerSpan = getObjectFactory().createSpan(
          JaegerTracer.this,
          operationName,
          context,
          startTimeMicroseconds,
          startTimeNanoTicks,
          computeDurationViaNanoTicks,
          tags,
          references);

      //  since context may be changed by explicitly provided sampling tag, we should use context from created span
      context = jaegerSpan.context();
      if (context.isSampled()) {
        if (isNewContext) {
          metrics.traceStartedSampled.inc(1);
        }
        metrics.spansStartedSampled.inc(1);
      } else {
        if (isNewContext) {
          metrics.traceStartedNotSampled.inc(1);
        }
        metrics.spansStartedNotSampled.inc(1);
      }
      return jaegerSpan;
    }

    @Deprecated
    // @Override keep compatibility with 0.32.0
    public Scope startActive(final boolean finishSpanOnClose) {
      if (!finishSpanOnClose) {
        return scopeManager.activate(start());
      }
      return new Scope() {
        Span span = start();
        Scope wrapped = scopeManager.activate(span);

        @Override
        public void close() {
          wrapped.close();
          span.finish();
        }

        // @Override keep compatibility with 0.32.0
        public Span span() {
          return span;
        }
      };
    }

    @Override
    public JaegerTracer.SpanBuilder ignoreActiveSpan() {
      ignoreActiveSpan = true;
      return this;
    }

    @Deprecated
    // @Override keep compatibility with 0.32.0
    public Span startManual() {
      return start();
    }

    private JaegerObjectFactory getObjectFactory() {
      return JaegerTracer.this.objectFactory;
    }

  }

  /**
   * Builds a {@link JaegerTracer} with options.
   */
  public static class Builder {
    private Sampler sampler;
    private Reporter reporter;
    private final PropagationRegistry registry = new PropagationRegistry();
    private Metrics metrics = new Metrics(new NoopMetricsFactory());
    private final String serviceName;
    private Clock clock = new SystemClock();
    private Map<String, Object> tags = new HashMap<String, Object>();
    private boolean zipkinSharedRpcSpan;
    private ScopeManager scopeManager = new ThreadLocalScopeManager();
    private BaggageRestrictionManager baggageRestrictionManager = new DefaultBaggageRestrictionManager();
    private boolean expandExceptionLogs;
    private final JaegerObjectFactory objectFactory;
    private boolean useTraceId128Bit;
    private boolean manualShutdown;

    public Builder(String serviceName) {
      this(serviceName, new JaegerObjectFactory());
    }

    protected Builder(String serviceName, JaegerObjectFactory objectFactory) {
      this.serviceName = checkValidServiceName(serviceName);
      this.objectFactory = objectFactory;

      TextMapCodec textMapCodec =
          TextMapCodec.builder()
              .withUrlEncoding(false)
              .withObjectFactory(this.objectFactory)
              .build();
      this.registerInjector(Format.Builtin.TEXT_MAP, textMapCodec);
      this.registerExtractor(Format.Builtin.TEXT_MAP, textMapCodec);
      TextMapCodec httpCodec =
          TextMapCodec.builder()
              .withUrlEncoding(true)
              .withObjectFactory(this.objectFactory)
              .build();
      this.registerInjector(Format.Builtin.HTTP_HEADERS, httpCodec);
      this.registerExtractor(Format.Builtin.HTTP_HEADERS, httpCodec);
      BinaryCodec binaryCodec =
          BinaryCodec.builder()
              .withObjectFactory(this.objectFactory)
              .build();
      this.registerInjector(Format.Builtin.BINARY, binaryCodec);
      this.registerExtractor(Format.Builtin.BINARY, binaryCodec);
    }

    /**
     * @param reporter reporter.
     */
    public Builder withReporter(Reporter reporter) {
      this.reporter = reporter;
      return this;
    }

    /**
     * @param sampler sampler.
     */
    public Builder withSampler(Sampler sampler) {
      this.sampler = sampler;
      return this;
    }

    public <T> Builder registerInjector(Format<T> format, Injector<T> injector) {
      this.registry.register(format, injector);
      return this;
    }

    public <T> Builder registerExtractor(Format<T> format, Extractor<T> extractor) {
      this.registry.register(format, extractor);
      return this;
    }

    /**
     * Creates a new {@link Metrics} to be used with the tracer, backed by the given {@link MetricsFactory}
     *
     * @param metricsFactory the metrics factory to use
     * @return this instance of the builder
     */
    public Builder withMetricsFactory(MetricsFactory metricsFactory) {
      this.metrics = new Metrics(metricsFactory);
      return this;
    }

    public Builder withScopeManager(ScopeManager scopeManager) {
      this.scopeManager = scopeManager;
      return this;
    }

    public Builder withClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    public Builder withZipkinSharedRpcSpan() {
      zipkinSharedRpcSpan = true;
      return this;
    }

    public Builder withExpandExceptionLogs() {
      this.expandExceptionLogs = true;
      return this;
    }

    public Builder withMetrics(Metrics metrics) {
      this.metrics = metrics;
      return this;
    }

    public Builder withTraceId128Bit() {
      this.useTraceId128Bit = true;
      return this;
    }

    public Builder withTag(String key, String value) {
      tags.put(key, value);
      return this;
    }

    public Builder withTag(String key, boolean value) {
      tags.put(key, value);
      return this;
    }

    public Builder withTag(String key, Number value) {
      tags.put(key, value);
      return this;
    }

    public Builder withTags(Map<String, String> tags) {
      if (tags != null) {
        this.tags.putAll(tags);
      }
      return this;
    }

    public Builder withBaggageRestrictionManager(BaggageRestrictionManager baggageRestrictionManager) {
      this.baggageRestrictionManager = baggageRestrictionManager;
      return this;
    }

    public Builder withManualShutdown() {
      this.manualShutdown = true;
      return this;
    }

    public JaegerTracer build() {
      if (reporter == null) {
        reporter = new RemoteReporter.Builder()
            .withMetrics(metrics)
            .build();
      }
      if (sampler == null) {
        sampler = new RemoteControlledSampler.Builder(serviceName)
            .withMetrics(metrics)
            .build();
      }
      return createTracer();
    }

    protected JaegerTracer createTracer() {
      return new JaegerTracer(this);
    }

    public static String checkValidServiceName(String serviceName) {
      if (serviceName == null || serviceName.trim().length() == 0) {
        throw new IllegalArgumentException("Service name must not be null or empty");
      }
      return serviceName;
    }
  }

  private static String loadVersion() {
    return "Java-" + getVersionFromProperties();
  }

  public static String getVersionFromProperties() {
    String version;
    try {
      InputStream is = JaegerTracer.class.getResourceAsStream("jaeger.properties");
      try {
        Properties prop = new Properties();
        prop.load(is);
        version = prop.getProperty(Constants.JAEGER_CLIENT_VERSION_TAG_KEY);
      } finally {
        is.close();
      }
    } catch (Exception e) {
      throw new RuntimeException("Cannot read jaeger.properties", e);
    }
    if (version == null) {
      throw new RuntimeException("Cannot read " + Constants.JAEGER_CLIENT_VERSION_TAG_KEY + " from jaeger.properties");
    }
    return version;
  }

  String getHostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      log.error("Cannot obtain host name", e);
      return null;
    }
  }

  JaegerSpanContext setBaggage(JaegerSpan jaegerSpan, String key, String value) {
    return baggageSetter.setBaggage(jaegerSpan, key, value);
  }

  boolean isExpandExceptionLogs() {
    return this.expandExceptionLogs;
  }

  public boolean isUseTraceId128Bit() {
    return this.useTraceId128Bit;
  }

  @Override
  public Scope activateSpan(Span span) {
    return scopeManager().activate(span);
  }
}
