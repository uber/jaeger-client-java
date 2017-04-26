/*
 * Copyright (c) 2016, Uber Technologies, Inc
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.jaeger;

import com.uber.jaeger.exceptions.UnsupportedFormatException;
import com.uber.jaeger.metrics.Metrics;
import com.uber.jaeger.metrics.NullStatsReporter;
import com.uber.jaeger.metrics.StatsFactoryImpl;
import com.uber.jaeger.metrics.StatsReporter;
import com.uber.jaeger.propagation.Extractor;
import com.uber.jaeger.propagation.Injector;
import com.uber.jaeger.propagation.TextMapCodec;
import com.uber.jaeger.reporters.Reporter;
import com.uber.jaeger.samplers.Sampler;
import com.uber.jaeger.samplers.SamplingStatus;
import com.uber.jaeger.utils.Clock;
import com.uber.jaeger.utils.SystemClock;
import com.uber.jaeger.utils.Utils;
import io.opentracing.References;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
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

@ToString(exclude = {"registry", "clock", "metrics"})
@Slf4j
public class Tracer implements io.opentracing.Tracer {

  private final String version;
  private final String serviceName;
  private final Reporter reporter;
  private final Sampler sampler;
  private final PropagationRegistry registry;
  private final Clock clock;
  private final Metrics metrics;
  private final int ip;
  private final Map<String, ?> tags;
  private final boolean zipkinSharedRpcSpan;

  private Tracer(
      String serviceName,
      Reporter reporter,
      Sampler sampler,
      PropagationRegistry registry,
      Clock clock,
      Metrics metrics,
      Map<String, Object> tags,
      boolean zipkinSharedRpcSpan) {
    this.serviceName = serviceName;
    this.reporter = reporter;
    this.sampler = sampler;
    this.registry = registry;
    this.clock = clock;
    this.metrics = metrics;
    this.zipkinSharedRpcSpan = zipkinSharedRpcSpan;

    int ip;
    try {
      ip = Utils.ipToInt(Inet4Address.getLocalHost().getHostAddress());
    } catch (UnknownHostException e) {
      ip = 0;
    }
    this.ip = ip;

    this.version = loadVersion();

    tags.put("jaeger.version", this.version);
    String hostname = getHostName();
    if (hostname != null) {
      tags.put("jaeger.hostname", hostname);
    }
    this.tags = Collections.unmodifiableMap(tags);
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

  public int getIp() {
    return ip;
  }

  Clock clock() {
    return clock;
  }

  Reporter getReporter() {
    return reporter;
  }

  void reportSpan(Span span) {
    reporter.report(span);
    metrics.spansFinished.inc(1);
  }

  @Override
  public io.opentracing.Tracer.SpanBuilder buildSpan(String operationName) {
    return new SpanBuilder(operationName);
  }

  @Override
  public <T> void inject(io.opentracing.SpanContext spanContext, Format<T> format, T carrier) {
    Injector<T> injector = registry.getInjector(format);
    if (injector == null) {
      throw new UnsupportedFormatException(format);
    }
    injector.inject((SpanContext) spanContext, carrier);
  }

  @Override
  public <T> io.opentracing.SpanContext extract(Format<T> format, T carrier) {
    Extractor<T> extractor = registry.getExtractor(format);
    if (extractor == null) {
      throw new UnsupportedFormatException(format);
    }
    return extractor.extract(carrier);
  }

  /**
   * Shuts down the {@link Reporter} and {@link Sampler}
   */
  public void close() {
    reporter.close();
    sampler.close();
  }

  //Visible for testing
  class SpanBuilder implements io.opentracing.Tracer.SpanBuilder {

    private String operationName = null;
    private long startTimeMicroseconds;
    private final List<Reference> references = new ArrayList<Reference>();
    private final Map<String, Object> tags = new HashMap<String, Object>();
    private final Map<String, String> baggage = new HashMap<String, String>();

    SpanBuilder(String operationName) {
      this.operationName = operationName;
    }

    @Override
    public Iterable<Map.Entry<String, String>> baggageItems() {
      return baggage.entrySet();
    }

    @Override
    public io.opentracing.Tracer.SpanBuilder asChildOf(io.opentracing.SpanContext parent) {
      return addReference(References.CHILD_OF, parent);
    }

    @Override
    public io.opentracing.Tracer.SpanBuilder asChildOf(io.opentracing.Span parent) {
      return addReference(References.CHILD_OF, parent.context());
    }

    @Override
    public io.opentracing.Tracer.SpanBuilder addReference(
        String referenceType, io.opentracing.SpanContext referencedContext) {
      if (referencedContext instanceof SpanContext
          && (Utils.equals(referenceType, References.CHILD_OF)
                      || Utils.equals(referenceType, References .FOLLOWS_FROM))) {
        this.references.add(new Reference((SpanContext) referencedContext, referenceType));
        this.baggage.putAll(((SpanContext)referencedContext).baggage());
      }

      return this;
    }

    @Override
    public io.opentracing.Tracer.SpanBuilder withTag(String key, String value) {
      tags.put(key, value);
      return this;
    }

    @Override
    public io.opentracing.Tracer.SpanBuilder withTag(String key, boolean value) {
      tags.put(key, value);
      return this;
    }

    @Override
    public io.opentracing.Tracer.SpanBuilder withTag(String key, Number value) {
      tags.put(key, value);
      return this;
    }

    @Override
    public io.opentracing.Tracer.SpanBuilder withStartTimestamp(long microseconds) {
      this.startTimeMicroseconds = microseconds;
      return this;
    }

    private SpanContext createNewContext(String debugId) {
      long id = Utils.uniqueId();

      byte flags = 0;
      if (debugId != null) {
        flags |= SpanContext.flagSampled | SpanContext.flagDebug;
        tags.put(Constants.DEBUG_ID_HEADER_KEY, debugId);
        metrics.traceStartedSampled.inc(1);
      } else {
        //TODO(prithvi): Don't assume operationName is set on creation
        SamplingStatus samplingStatus = sampler.sample(operationName, id);
        if (samplingStatus.isSampled()) {
          flags |= SpanContext.flagSampled;
          tags.putAll(samplingStatus.getTags());
          metrics.traceStartedSampled.inc(1);
        } else {
          metrics.traceStartedNotSampled.inc(1);
        }
      }

      return new SpanContext(id, id, 0, flags);
    }

    private SpanContext createChildContext(SpanContext preferredParent) {
      if (isRpcServer()) {
        if (preferredParent.isSampled()) {
          metrics.tracesJoinedSampled.inc(1);
        } else {
          metrics.tracesJoinedNotSampled.inc(1);
        }

        // Zipkin server compatibility
        if (zipkinSharedRpcSpan) {
          return preferredParent;
        }
      }
      return new SpanContext(
          preferredParent.getTraceId(),
          Utils.uniqueId(),
          preferredParent.getSpanId(),
          preferredParent.getFlags(),
          this.baggage,
          null);
    }

    //Visible for testing
    boolean isRpcServer() {
      return Tags.SPAN_KIND_SERVER.equals(tags.get(Tags.SPAN_KIND.getKey()));
    }

    @Override
    public io.opentracing.Span start() {
      // find preferredParent, it is first childOf
      Reference preferredParent = references.isEmpty() ? null : references.get(0);
      for (Reference reference: references) {
        if (References.CHILD_OF.equals(reference.getType())
            && !References.CHILD_OF.equals(preferredParent.getType())) {
          preferredParent = reference;
          break;
        }
      }

      SpanContext context;
      if (preferredParent == null) {
        context = createNewContext(null);
      } else if (preferredParent.getSpanContext().isDebugIdContainerOnly()) {
        context = createNewContext(preferredParent.getSpanContext().getDebugId());
      } else {
        context = createChildContext(preferredParent.getSpanContext());
      }

      long startTimeNanoTicks = 0;
      boolean computeDurationViaNanoTicks = false;

      if (startTimeMicroseconds == 0) {
        startTimeMicroseconds = clock.currentTimeMicros();
        if (!clock.isMicrosAccurate()) {
          startTimeNanoTicks = clock.currentNanoTicks();
          computeDurationViaNanoTicks = true;
        }
      }

      // TODO add tracer tags to zipkin first span in process
      if (zipkinSharedRpcSpan && (preferredParent == null || isRpcServer())) {
        tags.putAll(Tracer.this.tags);
      }

      Span span =
          new Span(
              Tracer.this,
              operationName,
              context,
              startTimeMicroseconds,
              startTimeNanoTicks,
              computeDurationViaNanoTicks,
              tags,
              references);
      if (context.isSampled()) {
        metrics.spansSampled.inc(1);
      } else {
        metrics.spansNotSampled.inc(1);
      }
      metrics.spansStarted.inc(1);
      return span;
    }
  }

  /**
   * Builds Jaeger Tracer with options.
   */
  public static final class Builder {
    private final Sampler sampler;
    private final Reporter reporter;
    private final PropagationRegistry registry = new PropagationRegistry();
    private Metrics metrics;
    private String serviceName;
    private Clock clock = new SystemClock();
    private Map<String, Object> tags = new HashMap<String, Object>();
    private boolean zipkinSharedRpcSpan;

    public Builder(String serviceName, Reporter reporter, Sampler sampler) {
      if (serviceName == null || serviceName.trim().length() == 0) {
        throw new IllegalArgumentException("serviceName must not be null or empty");
      }
      this.serviceName = serviceName;
      this.reporter = reporter;
      this.sampler = sampler;
      this.metrics = new Metrics(new StatsFactoryImpl(new NullStatsReporter()));

      TextMapCodec textMapCodec = new TextMapCodec(false);
      this.registerInjector(Format.Builtin.TEXT_MAP, textMapCodec);
      this.registerExtractor(Format.Builtin.TEXT_MAP, textMapCodec);
      TextMapCodec httpCodec = new TextMapCodec(true);
      this.registerInjector(Format.Builtin.HTTP_HEADERS, httpCodec);
      this.registerExtractor(Format.Builtin.HTTP_HEADERS, httpCodec);
      // TODO binary codec not implemented
    }

    public <T> Builder registerInjector(Format<T> format, Injector<T> injector) {
      this.registry.register(format, injector);
      return this;
    }

    public <T> Builder registerExtractor(Format<T> format, Extractor<T> extractor) {
      this.registry.register(format, extractor);
      return this;
    }

    public Builder withStatsReporter(StatsReporter statsReporter) {
      this.metrics = new Metrics(new StatsFactoryImpl(statsReporter));
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

    public Builder withMetrics(Metrics metrics) {
      this.metrics = metrics;
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

    public Tracer build() {
      return new Tracer(this.serviceName, reporter, sampler, registry, clock, metrics, tags,
          zipkinSharedRpcSpan);
    }
  }

  private static class PropagationRegistry {
    private final Map<Format<?>, Injector<?>> injectors = new HashMap<Format<?>, Injector<?>>();
    private final Map<Format<?>, Extractor<?>> extractors = new HashMap<Format<?>, Extractor<?>>();

    @SuppressWarnings("unchecked")
    <T> Injector<T> getInjector(Format<T> format) {
      return (Injector<T>) injectors.get(format);
    }

    @SuppressWarnings("unchecked")
    <T> Extractor<T> getExtractor(Format<T> format) {
      return (Extractor<T>) extractors.get(format);
    }

    public <T> void register(Format<T> format, Injector<T> injector) {
      injectors.put(format, injector);
    }

    public <T> void register(Format<T> format, Extractor<T> extractor) {
      extractors.put(format, extractor);
    }
  }

  private static String loadVersion() {
    String version;
    try {
      InputStream is = Tracer.class.getResourceAsStream("jaeger.properties");
      try {
        Properties prop = new Properties();
        prop.load(is);
        version = prop.getProperty("jaeger.version");
      } finally {
        is.close();
      }
    } catch (Exception e) {
      throw new RuntimeException("Cannot read jaeger.properties", e);
    }
    if (version == null) {
      throw new RuntimeException("Cannot read jaeger.version from jaeger.properties");
    }
    return "Java-" + version;
  }

  String getHostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      log.error("Cannot obtain host name", e);
      return null;
    }
  }
}
