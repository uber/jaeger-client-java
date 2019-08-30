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
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

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

  private JaegerSpan newSpan() {
    return tracer.buildSpan("x").start();
  }

  private static class FailingSender implements Sender {
    private static boolean invoked = false;

    @Override
    public int append(JaegerSpan span) throws SenderException {
      invoked = true;
      throw new SenderException("FailingSender is to fail", 1);
    }

    @Override
    public int flush() throws SenderException {
      throw new SenderException("FailingSender is to fail", 1);
    }

    @Override
    public int close() throws SenderException {
      throw new SenderException("FailingSender is to fail", 1);
    }

    public boolean invoked() {
      return invoked;
    }
  }

  @Test
  public void testReporterWithSenderException() {
    FailingSender failingSender = new FailingSender();
    Reporter failingReporter = new RemoteReporter.Builder()
        .withSender(failingSender)
        .withFlushInterval(flushInterval)
        .withMaxQueueSize(maxQueueSize)
        .withMetrics(metrics)
        .build();
    JaegerTracer failingTracer = new JaegerTracer.Builder("test-failing-reporter")
        .withReporter(failingReporter)
        .withSampler(new ConstSampler(true))
        .withMetrics(metrics)
        .build();
    JaegerSpan span = failingTracer.buildSpan("raza").start();
    failingReporter.report(span);
    // do sleep until automatic flush happens on 'reporter'
    // added 20ms on top of 'flushInterval' to avoid corner cases
    await()
        .with()
        .pollInterval(1, TimeUnit.MILLISECONDS)
        .atMost(flushInterval + 20, TimeUnit.MILLISECONDS)
        .until(() -> failingSender.invoked());

    assertEquals(true, failingSender.invoked());
    assertEquals(1, metricsFactory.getCounter("jaeger_tracer_reporter_spans", "result=err"));
  }
}
