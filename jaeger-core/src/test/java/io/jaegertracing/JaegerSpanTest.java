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

package io.jaegertracing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.jaegertracing.baggage.BaggageRestrictionManager;
import io.jaegertracing.baggage.DefaultBaggageRestrictionManager;
import io.jaegertracing.baggage.Restriction;
import io.jaegertracing.metrics.InMemoryMetricsFactory;
import io.jaegertracing.metrics.Metrics;
import io.jaegertracing.reporters.InMemoryReporter;
import io.jaegertracing.samplers.ConstSampler;
import io.jaegertracing.utils.Clock;
import io.opentracing.References;
import io.opentracing.log.Fields;
import io.opentracing.tag.Tags;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import uk.org.lidalia.slf4jext.Level;
import uk.org.lidalia.slf4jtest.LoggingEvent;
import uk.org.lidalia.slf4jtest.TestLogger;
import uk.org.lidalia.slf4jtest.TestLoggerFactory;

public class JaegerSpanTest {
  private static final TestLogger log = TestLoggerFactory.getTestLogger(JaegerSpan.class);
  private Clock clock;
  private InMemoryReporter reporter;
  private JaegerTracer tracer;
  private JaegerSpan jaegerSpan;
  private InMemoryMetricsFactory metricsFactory;
  private Metrics metrics;

  @Before
  public void setUp() {
    metricsFactory = new InMemoryMetricsFactory();
    reporter = new InMemoryReporter();
    clock = mock(Clock.class);
    metrics = new Metrics(metricsFactory);
    tracer = new JaegerTracer.Builder("SamplerTest")
            .withReporter(reporter)
            .withSampler(new ConstSampler(true))
            .withMetrics(metrics)
            .withClock(clock)
            .withBaggageRestrictionManager(new DefaultBaggageRestrictionManager())
            .withExpandExceptionLogs()
            .build();
    jaegerSpan = tracer.buildSpan("some-operation").start();
  }

  @After
  public void clearLoggers() {
    TestLoggerFactory.clear();
  }

  @Test
  public void testSpanMetrics() {
    assertEquals(1, metricsFactory.getCounter("jaeger:started_spans", "sampled=y"));
    assertEquals(1, metricsFactory.getCounter("jaeger:traces", "sampled=y,state=started"));
  }

  @Test
  public void testSetAndGetBaggageItem() {
    final String service = "SamplerTest";
    final BaggageRestrictionManager mgr = Mockito.mock(DefaultBaggageRestrictionManager.class);
    tracer = new JaegerTracer.Builder(service)
            .withReporter(reporter)
            .withSampler(new ConstSampler(true))
            .withClock(clock)
            .withBaggageRestrictionManager(mgr)
            .build();
    jaegerSpan = tracer.buildSpan("some-operation").start();

    final String key = "key";
    final String value = "value";
    when(mgr.getRestriction(service, key)).thenReturn(Restriction.of(true, 10));
    jaegerSpan.setBaggageItem(key, "value");
    verify(mgr).getRestriction(service, key);
    assertEquals(value, jaegerSpan.getBaggageItem(key));
  }

  @Test
  public void testSetBooleanTag() {
    Boolean expected = true;
    String key = "tag.key";

    jaegerSpan.setTag(key, expected);
    assertEquals(expected, jaegerSpan.getTags().get(key));
  }

  @Test
  public void testSetOperationName() {
    String expected = "modified.operation";

    assertEquals("some-operation", jaegerSpan.getOperationName());
    jaegerSpan.setOperationName(expected);
    assertEquals(expected, jaegerSpan.getOperationName());
  }

  @Test
  public void testSetOperationNameAfterrFinish() {
    String unexpected = "modified.operation";

    assertEquals("some-operation", jaegerSpan.getOperationName());
    jaegerSpan.finish();
    jaegerSpan.setOperationName(unexpected);
    assertEquals("some-operation", jaegerSpan.getOperationName());
  }

  @Test
  public void testSetStringTag() {
    String expected = "expected.value";
    String key = "tag.key";

    jaegerSpan.setTag(key, expected);
    assertEquals(expected, jaegerSpan.getTags().get(key));
  }

  @Test
  public void testSetNumberTag() {
    Integer expected = 5;
    String key = "tag.key";

    jaegerSpan.setTag(key, expected);
    assertEquals(expected, jaegerSpan.getTags().get(key));
  }

  @Test
  public void testSetStringTagAfterFinish() {
    String value = "unexpected.value";
    String key = "tag.key";

    jaegerSpan.finish();
    jaegerSpan.setTag(key, value);
    assertEquals(null, jaegerSpan.getTags().get(key));
  }

  @Test
  public void testWithTimestampAccurateClock() {
    testWithTimestamp(true);
  }

  @Test
  public void testWithTimestampInaccurateClock() {
    testWithTimestamp(false);
  }

  private void testWithTimestamp(boolean accurate) {
    when(clock.isMicrosAccurate()).thenReturn(accurate);
    when(clock.currentTimeMicros())
        .thenThrow(new IllegalStateException("currentTimeMicros() called"));
    when(clock.currentNanoTicks())
        .thenThrow(new IllegalStateException("currentNanoTicks() called"));

    JaegerSpan jaegerSpan = tracer.buildSpan("test-service-name").withStartTimestamp(567).start();
    jaegerSpan.finish(999);

    assertEquals(1, reporter.getSpans().size());
    assertEquals(567, jaegerSpan.getStart());
    assertEquals(999 - 567, jaegerSpan.getDuration());
  }

  @Test
  public void testMultipleSpanFinishDoesNotCauseMultipleReportCalls() {
    JaegerSpan jaegerSpan = tracer.buildSpan("test-service-name").start();
    jaegerSpan.finish();

    assertEquals(1, reporter.getSpans().size());

    JaegerSpan reportedJaegerSpan = reporter.getSpans().get(0);

    // new finish calls will not affect size of reporter.getJaegerSpans()
    jaegerSpan.finish();

    assertEquals(1, reporter.getSpans().size());
    assertEquals(reportedJaegerSpan, reporter.getSpans().get(0));
  }

  @Test
  public void testWithoutTimestampsAccurateClock() {
    when(clock.isMicrosAccurate()).thenReturn(true);
    when(clock.currentTimeMicros()).thenReturn(1L).thenReturn(5L);
    when(clock.currentNanoTicks())
        .thenThrow(new IllegalStateException("currentNanoTicks() called"));

    JaegerSpan jaegerSpan = tracer.buildSpan("test-service-name").start();
    jaegerSpan.finish();

    assertEquals(1, reporter.getSpans().size());
    assertEquals(1, jaegerSpan.getStart());
    assertEquals(4, jaegerSpan.getDuration());
  }

  @Test
  public void testWithoutTimestampsInaccurateClock() {
    when(clock.isMicrosAccurate()).thenReturn(false);
    when(clock.currentTimeMicros())
        .thenReturn(100L)
        .thenThrow(new IllegalStateException("currentTimeMicros() called 2nd time"));
    when(clock.currentNanoTicks()).thenReturn(20000L).thenReturn(30000L);

    JaegerSpan jaegerSpan = tracer.buildSpan("test-service-name").start();
    jaegerSpan.finish();

    assertEquals(1, reporter.getSpans().size());
    assertEquals(100, jaegerSpan.getStart());
    assertEquals(10, jaegerSpan.getDuration());
  }

  @Test
  public void testSpanToString() {
    JaegerSpan jaegerSpan = tracer.buildSpan("test-operation").start();
    JaegerSpanContext expectedContext =  jaegerSpan.context();
    JaegerSpanContext actualContext = JaegerSpanContext.contextFromString(expectedContext.contextAsString());

    assertEquals(expectedContext.getTraceId(), actualContext.getTraceId());
    assertEquals(expectedContext.getSpanId(), actualContext.getSpanId());
    assertEquals(expectedContext.getParentId(), actualContext.getParentId());
    assertEquals(expectedContext.getFlags(), actualContext.getFlags());
  }

  @Test
  public void testOperationName() {
    String expectedOperation = "leela";
    JaegerSpan jaegerSpan = tracer.buildSpan(expectedOperation).start();
    assertEquals(expectedOperation, jaegerSpan.getOperationName());
  }

  @Test
  public void testLogWithTimestamp() {
    long expectedTimestamp = 2222;
    final String expectedLog = "some-log";
    final String expectedEvent = "event";
    Map<String, String> expectedFields = new HashMap<String, String>() {
      {
        put(expectedEvent, expectedLog);
      }
    };

    jaegerSpan.log(expectedTimestamp, expectedEvent);
    jaegerSpan.log(expectedTimestamp, expectedFields);
    jaegerSpan.log(expectedTimestamp, (String) null);
    jaegerSpan.log(expectedTimestamp, (Map<String, ?>) null);

    LogData actualLogData = jaegerSpan.getLogs().get(0);

    assertEquals(expectedTimestamp, actualLogData.getTime());
    assertEquals(expectedEvent, actualLogData.getMessage());

    actualLogData = jaegerSpan.getLogs().get(1);

    assertEquals(expectedTimestamp, actualLogData.getTime());
    assertNull(actualLogData.getMessage());
    assertEquals(expectedFields, actualLogData.getFields());
  }

  @Test
  public void testLog() {
    final long expectedTimestamp = 2222;
    final String expectedLog = "some-log";
    final String expectedEvent = "expectedEvent";

    when(clock.currentTimeMicros()).thenReturn(expectedTimestamp);

    jaegerSpan.log(expectedEvent);

    Map<String, String> expectedFields = Collections.singletonMap(expectedEvent, expectedLog);
    jaegerSpan.log(expectedFields);
    jaegerSpan.log((String) null);
    jaegerSpan.log((Map<String, ?>) null);

    LogData actualLogData = jaegerSpan.getLogs().get(0);

    assertEquals(expectedTimestamp, actualLogData.getTime());
    assertEquals(expectedEvent, actualLogData.getMessage());

    actualLogData = jaegerSpan.getLogs().get(1);

    assertEquals(expectedTimestamp, actualLogData.getTime());
    assertNull(actualLogData.getMessage());
    assertEquals(expectedFields, actualLogData.getFields());
  }

  @Test
  public void testLogAfterFinish() {
    final long expectedTimestamp = 2222;
    final String expectedLog = "some-log";
    final String expectedEvent = "expectedEvent";

    when(clock.currentTimeMicros()).thenReturn(expectedTimestamp);

    jaegerSpan.finish();
    jaegerSpan.log(expectedEvent);
    assertNull(jaegerSpan.getLogs());
  }

  @Test
  public void testLogWithTimestampAfterFinish() {
    Map<String, String> unexpectedFields = Collections.singletonMap("foo", "bar");

    jaegerSpan.finish();
    jaegerSpan.log(2222, unexpectedFields);
    assertNull(jaegerSpan.getLogs());
  }

  @Test
  public void testSpanDetectsSamplingPriorityGreaterThanZero() {
    JaegerSpan jaegerSpan = tracer.buildSpan("test-service-operation").start();
    Tags.SAMPLING_PRIORITY.set(jaegerSpan, 1);

    JaegerSpanContext context = jaegerSpan.context();
    assertEquals(context.getFlags() & JaegerSpanContext.flagSampled, JaegerSpanContext.flagSampled);
    assertEquals(context.getFlags() & JaegerSpanContext.flagDebug, JaegerSpanContext.flagDebug);
  }

  @Test
  public void testSpanDetectsSamplingPriorityLessThanZero() {
    // setup
    JaegerSpan jaegerSpan = tracer.buildSpan("test-service-operation").start();

    // sanity check
    assertEquals(
        JaegerSpanContext.flagSampled,
        jaegerSpan.context().getFlags() & JaegerSpanContext.flagSampled
    );

    // test
    Tags.SAMPLING_PRIORITY.set(jaegerSpan, -1);

    // verify
    assertEquals(
        0,
        jaegerSpan.context().getFlags() & JaegerSpanContext.flagSampled
    );
  }

  @Test
  public void testBaggageOneReference() {
    JaegerSpan parent = tracer.buildSpan("foo").start();
    parent.setBaggageItem("foo", "bar");

    JaegerSpan child = tracer.buildSpan("foo")
        .asChildOf(parent)
        .start();

    child.setBaggageItem("a", "a");

    assertNull(parent.getBaggageItem("a"));
    assertEquals("a", child.getBaggageItem("a"));
    assertEquals("bar", child.getBaggageItem("foo"));
  }

  @Test
  public void testBaggageMultipleReferences() {
    JaegerSpan parent1 = tracer.buildSpan("foo").start();
    parent1.setBaggageItem("foo", "bar");
    JaegerSpan parent2 = tracer.buildSpan("foo").start();
    parent2.setBaggageItem("foo2", "bar");

    JaegerSpan child = tracer.buildSpan("foo")
        .asChildOf(parent1)
        .addReference(References.FOLLOWS_FROM, parent2.context())
        .start();

    child.setBaggageItem("a", "a");
    child.setBaggageItem("foo2", "b");

    assertNull(parent1.getBaggageItem("a"));
    assertNull(parent2.getBaggageItem("a"));
    assertEquals("a", child.getBaggageItem("a"));
    assertEquals("bar", child.getBaggageItem("foo"));
    assertEquals("b", child.getBaggageItem("foo2"));
  }

  @Test
  public void testImmutableBaggage() {
    JaegerSpan span = tracer.buildSpan("foo").start();
    span.setBaggageItem("foo", "bar");
    {
      Iterator<Entry<String, String>> baggageIter = span.context().baggageItems().iterator();
      baggageIter.next();
      baggageIter.remove();
    }

    Iterator<Entry<String, String>> baggageIter = span.context().baggageItems().iterator();
    baggageIter.next();
    assertFalse(baggageIter.hasNext());
  }

  @Test
  public void testSetBaggageAfterFinish() {
    jaegerSpan.finish();
    jaegerSpan.setBaggageItem("foo", "bar");

    assertNull(jaegerSpan.getBaggageItem("foo"));
  }

  @Test
  public void testExpandExceptionLogs() {
    RuntimeException ex = new RuntimeException(new NullPointerException("npe"));
    Map<String, Object> logs = new HashMap<>();
    logs.put(Fields.ERROR_OBJECT, ex);
    JaegerSpan jaegerSpan = tracer.buildSpan("foo").start();
    jaegerSpan.log(logs);

    List<LogData> logData = jaegerSpan.getLogs();
    assertEquals(1, logData.size());
    assertEquals(4, logData.get(0).getFields().size());

    assertEquals(ex, logData.get(0).getFields().get(Fields.ERROR_OBJECT));
    assertEquals(ex.getMessage(), logData.get(0).getFields().get(Fields.MESSAGE));
    assertEquals(ex.getClass().getName(), logData.get(0).getFields().get(Fields.ERROR_KIND));
    StringWriter sw = new StringWriter();
    ex.printStackTrace(new PrintWriter(sw));
    assertEquals(sw.toString(), logData.get(0).getFields().get(Fields.STACK));
  }

  @Test
  public void testExpandExceptionLogsExpanded() {
    RuntimeException ex = new RuntimeException(new NullPointerException("npe"));
    Map<String, Object> logs = new HashMap<>();
    logs.put(Fields.ERROR_OBJECT, ex);
    logs.put(Fields.MESSAGE, ex.getMessage());
    logs.put(Fields.ERROR_KIND, ex.getClass().getName());
    StringWriter sw = new StringWriter();
    ex.printStackTrace(new PrintWriter(sw));
    logs.put(Fields.STACK, sw.toString());
    JaegerSpan jaegerSpan = tracer.buildSpan("foo").start();
    jaegerSpan.log(logs);

    List<LogData> logData = jaegerSpan.getLogs();
    assertEquals(1, logData.size());
    assertEquals(4, logData.get(0).getFields().size());

    assertEquals(ex, logData.get(0).getFields().get(Fields.ERROR_OBJECT));
    assertEquals(ex.getMessage(), logData.get(0).getFields().get(Fields.MESSAGE));
    assertEquals(ex.getClass().getName(), logData.get(0).getFields().get(Fields.ERROR_KIND));
    assertEquals(sw.toString(), logData.get(0).getFields().get(Fields.STACK));
  }

  @Test
  public void testExpandExceptionLogsLoggedNoException() {
    JaegerSpan jaegerSpan = tracer.buildSpan("foo").start();

    Object object = new Object();
    Map<String, Object> logs = new HashMap<>();
    logs.put(Fields.ERROR_OBJECT, object);
    jaegerSpan.log(logs);

    List<LogData> logData = jaegerSpan.getLogs();
    assertEquals(1, logData.size());
    assertEquals(1, logData.get(0).getFields().size());
    assertEquals(object, logData.get(0).getFields().get(Fields.ERROR_OBJECT));
  }

  @Test
  public void testNoExpandExceptionLogs() {
    JaegerTracer tracer = new JaegerTracer.Builder("fo")
        .withReporter(reporter)
        .withSampler(new ConstSampler(true))
        .build();

    JaegerSpan jaegerSpan = tracer.buildSpan("foo").start();

    RuntimeException ex = new RuntimeException();
    Map<String, Object> logs = new HashMap<>();
    logs.put(Fields.ERROR_OBJECT, ex);
    jaegerSpan.log(logs);

    List<LogData> logData = jaegerSpan.getLogs();
    assertEquals(1, logData.size());
    assertEquals(1, logData.get(0).getFields().size());
    assertEquals(ex, logData.get(0).getFields().get(Fields.ERROR_OBJECT));
  }

  @Test
  public void testNoLoggingingStackTracesOnDebug() {
    log.setEnabledLevels(Level.WARN);
    JaegerSpan jaegerSpan = tracer.buildSpan("foo").start();
    jaegerSpan.finish();
    jaegerSpan.finish();

    assertEquals(1, log.getLoggingEvents().size());
    LoggingEvent event = log.getLoggingEvents().get(0);
    assertEquals("Span has already been finished; cannot {}.", event.getMessage());
    assertEquals(Level.WARN, event.getLevel());
    assertEquals(false, event.getThrowable().isPresent());
  }

  @Test
  public void testLoggingingStackTracesOnDebug() {
    log.setEnabledLevels(Level.WARN, Level.DEBUG);
    JaegerSpan jaegerSpan = tracer.buildSpan("foo").start();
    jaegerSpan.finish();
    jaegerSpan.finish();

    assertEquals(2, log.getLoggingEvents().size());
    LoggingEvent event = log.getLoggingEvents().get(0);
    assertEquals("Span has already been finished; cannot {}.", event.getMessage());
    assertEquals(Level.WARN, event.getLevel());
    assertEquals(false, event.getThrowable().isPresent());
    event = log.getLoggingEvents().get(1);
    assertEquals("Generating stack trace because we cannot {}.", event.getMessage());
    assertEquals(Level.DEBUG, event.getLevel());
    assertEquals("Called from:", event.getThrowable().get().getMessage());
  }

  @Test
  public void testSpanNotSampled() {
    JaegerTracer tracer = new JaegerTracer.Builder("fo")
        .withReporter(reporter)
        .withSampler(new ConstSampler(false))
        .build();
    JaegerSpan foo = tracer.buildSpan("foo")
        .start();
    foo.log(Collections.emptyMap())
        .finish();
    assertEquals(0, reporter.getSpans().size());
  }
}
