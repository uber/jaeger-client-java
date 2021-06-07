/*
 * Copyright (c) 2016-2017, Uber Technologies, Inc
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

package io.jaegertracing.internal.reporters;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import io.jaegertracing.internal.JaegerSpan;
import io.jaegertracing.internal.JaegerTracer;
import io.jaegertracing.internal.exceptions.SenderException;
import io.jaegertracing.internal.metrics.InMemoryMetricsFactory;
import io.jaegertracing.internal.metrics.Metrics;
import io.jaegertracing.internal.reporters.RemoteReporter.Builder;
import io.jaegertracing.internal.samplers.ConstSampler;
import io.jaegertracing.internal.senders.InMemorySender;
import io.jaegertracing.spi.Reporter;
import io.jaegertracing.spi.Sender;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;
import org.slf4j.LoggerFactory;

public class RemoteReporterTest {
  private Reporter reporter;
  private JaegerTracer tracer;
  private InMemorySender sender;
  private final int flushInterval = 1000; // in milliseconds
  private final int maxQueueSize = 500;
  private Metrics metrics;
  private InMemoryMetricsFactory metricsFactory;

  @Before
  public void setUp() {
    metricsFactory = new InMemoryMetricsFactory();
    metrics = new Metrics(metricsFactory);

    sender = new InMemorySender();
    reporter = new RemoteReporter.Builder()
        .withSender(sender)
        .withFlushInterval(flushInterval)
        .withMaxQueueSize(maxQueueSize)
        .withMetrics(metrics)
        .build();
    tracer = new JaegerTracer.Builder("test-remote-reporter")
            .withReporter(reporter)
            .withSampler(new ConstSampler(true))
            .withMetrics(metrics)
            .build();
  }

  @Test
  public void testRemoteReporterReport() {
    JaegerSpan span = tracer.buildSpan("raza").start();
    reporter.report(span);
    // do sleep until automatic flush happens on 'reporter'
    // added 20ms on top of 'flushInterval' to avoid corner cases
    await()
        .with()
        .pollInterval(1, TimeUnit.MILLISECONDS)
        .atMost(flushInterval + 20, TimeUnit.MILLISECONDS)
        .until(() -> sender.getReceived().size() > 0);
    List<JaegerSpan> received = sender.getReceived();

    assertEquals(1, received.size());
  }

  @Test
  public void testRemoteReporterFlushesOnClose() {
    int numberOfSpans = 100;
    for (int i = 0; i < numberOfSpans; i++) {
      JaegerSpan span = tracer.buildSpan("raza").start();
      reporter.report(span);
    }
    reporter.close();

    assertEquals(0, sender.getAppended().size());
    assertEquals(numberOfSpans, sender.getFlushed().size());

    assertEquals(100, metricsFactory.getCounter("jaeger_tracer_started_spans", "sampled=y"));
    assertEquals(100, metricsFactory.getCounter("jaeger_tracer_reporter_spans", "result=ok"));
    assertEquals(100, metricsFactory.getCounter("jaeger_tracer_traces", "sampled=y,state=started"));
  }

  @Test
  public void testRemoteReporterFlushTimerThread() {
    int flushTimerThreadCount = 0;
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (!thread.getName().equals("jaeger.RemoteReporter-FlushTimer")) {
        continue;
      }
      ++flushTimerThreadCount;
      assertTrue(thread.isDaemon());
    }
    assertFalse(flushTimerThreadCount == 0);
  }

  // Starts a number of threads. Each can fill the queue on its own, so they will exceed its
  // capacity many times over
  @Test
  public void testReportDoesntThrowWhenQueueFull() throws Exception {
    final AtomicBoolean exceptionWasThrown = new AtomicBoolean(false);

    int threadsCount = 10;
    final CyclicBarrier barrier = new CyclicBarrier(threadsCount);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < threadsCount; i++) {
      Thread t = createSpanReportingThread(exceptionWasThrown, barrier);
      threads.add(t);
      t.start();
    }

    for (Thread t : threads) {
      t.join();
    }

    assertFalse(exceptionWasThrown.get());
  }

  private Thread createSpanReportingThread(final AtomicBoolean exceptionWasThrown,
      final CyclicBarrier barrier) {
    return new Thread(new Runnable() {
          @Override
          public void run() {
            for (int x = 0; x < maxQueueSize; x++) {
              try {
                barrier.await();
                reporter.report(newSpan());
              } catch (Throwable e) {
                e.printStackTrace();
                exceptionWasThrown.set(true);
              }
            }
          }
        });
  }

  @Test
  public void testAppendWhenQueueFull() {
    // change sender to blocking mode
    sender.permitAppend(0);

    for (int i = 0; i < maxQueueSize; i++) {
      reporter.report(newSpan());
    }

    // When: at this point the queue is full or there is one slot empty (if the worker thread has
    // already picked up some command). We add two spans to make sure that we overfill the queue
    reporter.report(newSpan());
    reporter.report(newSpan());

    // Then: one or both spans should be dropped
    long droppedCount = metricsFactory.getCounter("jaeger_tracer_reporter_spans", "result=dropped");
    assertThat(droppedCount, anyOf(equalTo(1L), equalTo(2L)));
  }

  @Test
  @Ignore("See https://github.com/jaegertracing/jaeger-client-java/issues/340")
  public void testCloseWhenQueueFull() {
    int closeTimeoutMillis = 5;
    reporter = new RemoteReporter.Builder()
        .withSender(sender)
        .withFlushInterval(Integer.MAX_VALUE)
        .withMaxQueueSize(maxQueueSize)
        .withCloseEnqueueTimeout(closeTimeoutMillis)
        .withMetrics(metrics)
        .build();
    tracer = new JaegerTracer.Builder("test-remote-reporter")
        .withReporter(reporter)
        .withSampler(new ConstSampler(true))
        .withMetrics(metrics)
        .build();
    // change sender to blocking mode
    sender.permitAppend(0);

    // fill the queue
    for (int i = 0; i < maxQueueSize + 10; i++) {
      reporter.report(newSpan());
    }

    reporter.close();

    // expect no exception thrown
  }

  @Test
  public void testCloseLogSenderException() throws SenderException {

    Appender<ILoggingEvent> mockAppender = mockLogger(e -> {
    });

    final Sender mockedSender = mock(Sender.class);
    when(mockedSender.close()).thenThrow(SenderException.class);

    final RemoteReporter testReporter = new RemoteReporter.Builder()
        .withSender(mockedSender)
        .build();

    // call the to be tested code path
    testReporter.close();

    // verify the actual log was written
    verify(mockAppender).doAppend(argThat(new ArgumentMatcher<ILoggingEvent>() {

      @Override
      public boolean matches(ILoggingEvent event) {
        return event.getFormattedMessage().startsWith("Remote reporter error");
      }

    }));
  }

  @Test
  public void testFlushWhenQueueFull() {
    // change sender to blocking mode
    sender.permitAppend(0);

    // fill the queue
    for (int i = 0; i < maxQueueSize + 10; i++) {
      reporter.report(newSpan());
    }

    ((RemoteReporter) reporter).flush();

    // expect no exception thrown
  }

  @Test
  public void testFlushUpdatesQueueLength() {
    int neverFlushInterval = Integer.MAX_VALUE;
    reporter = new RemoteReporter.Builder()
        .withSender(sender)
        .withFlushInterval(neverFlushInterval)
        .withMaxQueueSize(maxQueueSize)
        .withMetrics(metrics)
        .build();
    tracer = new JaegerTracer.Builder("test-remote-reporter")
        .withReporter(reporter)
        .withSampler(new ConstSampler(true))
        .withMetrics(metrics)
        .build();

    // change sender to blocking mode
    sender.permitAppend(0);

    for (int i = 0; i < 3; i++) {
      reporter.report(newSpan());
    }

    assertEquals(0, metricsFactory.getGauge("jaeger_tracer_reporter_queue_length", ""));

    RemoteReporter remoteReporter = (RemoteReporter) reporter;
    remoteReporter.flush();

    assertTrue(metricsFactory.getGauge("jaeger_tracer_reporter_queue_length", "") > 0);
  }

  @Test
  public void testFlushIsCalledOnSender() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);

    InMemorySender sender = new InMemorySender() {
      @Override
      public int flush() throws SenderException {
        latch.countDown();
        return super.flush();
      }
    };

    RemoteReporter remoteReporter = new Builder()
        .withSender(sender)
        .withFlushInterval(flushInterval)
        .withMaxQueueSize(maxQueueSize)
        .withMetrics(metrics)
        .build();
    tracer = new JaegerTracer.Builder("test-remote-reporter")
        .withReporter(remoteReporter)
        .withSampler(new ConstSampler(true))
        .withMetrics(metrics)
        .build();

    tracer.buildSpan("mySpan").start().finish();
    remoteReporter.flush();
    latch.await();
    assertEquals("Should have called the custom sender flush", 0, latch.getCount());
    assertEquals("mySpan", (sender.getReceived().get(0)).getOperationName());
  }

  @Test
  public void testFlushErrorsLoggedJustOnce() throws InterruptedException {

    Object logMonitor = new Object();
    AtomicReference<String> logMsg = new AtomicReference<>(null);
    mockLogger(e -> {
      synchronized (logMonitor) {
        logMsg.set(e.getFormattedMessage());
        logMonitor.notifyAll();
      }
    });

    class FailingSender extends InMemorySender {
      private final AtomicInteger flushCounter = new AtomicInteger(0);

      @Override
      public int flush() throws SenderException {
        int i = super.flush();
        switch (flushCounter.getAndIncrement()) {
          case 1:
          case 2:
          case 3:
            throw new SenderException("test1", i);
          default:
            return i;
        }
      }

      private String awaitMessage(AtomicReference<String> ref) throws InterruptedException {
        synchronized (logMonitor) {
          while (ref.get() == null) {
            logMonitor.wait();
          }
          return ref.getAndSet(null);
        }
      }
    }

    FailingSender sender = new FailingSender();

    RemoteReporter remoteReporter = new Builder()
        .withSender(sender)
        .withFlushInterval(Integer.MAX_VALUE)
        .withMaxQueueSize(maxQueueSize)
        .withMetrics(metrics)
        .build();
    tracer = new JaegerTracer.Builder("test-remote-reporter")
        .withReporter(remoteReporter)
        .withSampler(new ConstSampler(true))
        .withMetrics(metrics)
        .build();

    assertEquals(0, sender.flushCounter.get());
    tracer.buildSpan("mySpan").start().finish();
    remoteReporter.flush();

    // 1. SenderException is thrown (log should be produced)
    await()
      .with()
      .pollInterval(1, TimeUnit.MILLISECONDS)
      .until(() ->  sender.flushCounter.get() == 1);
    tracer.buildSpan("mySpan").start().finish();
    remoteReporter.flush();
    assertEquals("FlushCommand execution failed! Repeated errors of this command will not be logged.",
            sender.awaitMessage(logMsg));

    // 2. SenderException is thrown (log should not be produced)
    await()
      .with()
      .pollInterval(1, TimeUnit.MILLISECONDS)
      .until(() ->  sender.flushCounter.get() == 2);
    remoteReporter.flush();

    // 3. SenderException is thrown (log should not be produced)
    await()
      .with()
      .pollInterval(1, TimeUnit.MILLISECONDS)
      .until(() ->  sender.flushCounter.get() == 3);
    remoteReporter.flush();

    // No SenderException is thrown now, but 0 span is ready -> still stay in failing state
    await()
      .with()
      .pollInterval(1, TimeUnit.MILLISECONDS)
      .until(() ->  sender.flushCounter.get() == 4);
    remoteReporter.flush();

    // No SenderException is thrown now, but 1 span is ready -> move to working state
    tracer.buildSpan("mySpan").start().finish();
    remoteReporter.flush();
    assertEquals("FlushCommand is working again!", sender.awaitMessage(logMsg));
    await()
      .with()
      .pollInterval(1, TimeUnit.MILLISECONDS)
      .until(() ->  sender.getFlushed().size() == 3);
  }

  @Test
  public void testUpdateSuccessMetricWhenAppendFlushed() throws InterruptedException {
    int totalSpans = 3;
    int flushSize = 2;

    CountDownLatch latch = new CountDownLatch(1);
    sender = new InMemorySender() {
      @Override
      public int append(JaegerSpan span) {
        try {
          super.append(span);
          if (getAppended().size() >= flushSize) {
            return flush();
          }
          if (getReceived().size() == totalSpans) {
            latch.countDown();
          }
          return 0;
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    };
    reporter = new Builder()
            .withSender(sender)
            // disable scheduled flush
            .withFlushInterval(Integer.MAX_VALUE)
            .withMaxQueueSize(maxQueueSize)
            .withMetrics(metrics)
            .build();
    tracer = new JaegerTracer.Builder("test-remote-reporter")
            .withReporter(reporter)
            .withSampler(new ConstSampler(true))
            .withMetrics(metrics)
            .build();

    for (int i = 0; i < totalSpans; i++) {
      reporter.report(newSpan());
    }

    latch.await(1, TimeUnit.SECONDS);
    assertEquals(flushSize, metricsFactory.getCounter("jaeger_tracer_reporter_spans", "result=ok"));
    assertEquals(0, metricsFactory.getCounter("jaeger_tracer_reporter_spans", "result=err"));
    assertEquals(0, metricsFactory.getCounter("jaeger_tracer_reporter_spans", "result=dropped"));
  }

  @Test
  public void testUpdateErrorMetricWhenCommandExecuteFails() throws Exception {

    int reporterFailures = 5;

    sender = new InMemorySender() {
      @Override
      public int append(JaegerSpan span) throws SenderException {
        throw new SenderException("", reporterFailures);
      }
    };

    RemoteReporter reporter = new Builder()
            .withSender(sender)
            .withFlushInterval(flushInterval)
            .withMaxQueueSize(maxQueueSize)
            .withMetrics(metrics)
            .build();

    reporter.report(newSpan());
    reporter.close();

    assertEquals(reporterFailures, metricsFactory.getCounter("jaeger_tracer_reporter_spans", "result=err"));
  }

  private JaegerSpan newSpan() {
    return tracer.buildSpan("x").start();
  }

  private static Appender<ILoggingEvent> mockLogger(Consumer<ILoggingEvent> append) {
    ch.qos.logback.classic.Logger root =
            (ch.qos.logback.classic.Logger) LoggerFactory
                    .getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);

    @SuppressWarnings("unchecked")
    final Appender<ILoggingEvent> mockAppender = mock(Appender.class);
    when(mockAppender.getName()).thenReturn("MOCK");
    doAnswer(i -> {
      append.accept(i.getArgument(0));
      return null;
    }).when(mockAppender).doAppend(ArgumentMatchers.any(ILoggingEvent.class));
    root.addAppender(mockAppender);

    return mockAppender;
  }
}
