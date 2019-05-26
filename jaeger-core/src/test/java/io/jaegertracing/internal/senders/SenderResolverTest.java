/*
 * Copyright (c) 2018, The Jaeger Authors
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

package io.jaegertracing.internal.senders;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.jaegertracing.Configuration;
import io.jaegertracing.internal.JaegerSpan;
import io.jaegertracing.spi.Sender;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import org.junit.After;
import org.junit.Test;

public class SenderResolverTest {

  @After
  public void clear() {
    SenderFactoryToBeLoaded.sender = new NoopSender();
    System.clearProperty(Configuration.JAEGER_SENDER_FACTORY);
  }

  @Test
  public void testNoImplementations() throws Exception {
    Sender sender = getSenderForServiceFileContents("", false);
    assertTrue("Expected to have sender as instance of NoopSender, but was " + sender.getClass(),
        sender instanceof NoopSender);
  }

  @Test
  public void testFaultySenderFactory() throws Exception {
    Sender sender = getSenderForServiceFileContents(FaultySenderFactory.class.getName(), false);
    assertTrue("Expected to have sender as instance of NoopSender, but was " + sender.getClass(),
        sender instanceof NoopSender);
  }

  @Test
  public void testNoopSenderFactory() throws Exception {
    System.setProperty(Configuration.JAEGER_SENDER_FACTORY, "noop");

    Sender sender = getSenderForServiceFileContents("\n" + NoopSenderFactory.class.getName(), true);
    SenderResolver.resolve();
    assertTrue("Expected to have sender as instance of NoopSender, but was " + sender.getClass(),
        sender instanceof NoopSender);

    System.clearProperty(Configuration.JAEGER_SENDER_FACTORY);
  }

  @Test
  public void testFromServiceLoader() {
    CustomSender customSender = new CustomSender();
    SenderFactoryToBeLoaded.sender = customSender;
    Sender sender = SenderResolver.resolve();
    assertEquals(customSender, sender);
  }

  @Test
  public void testMultipleImplementationsAmbiguous() throws Exception {
    SenderFactoryToBeLoaded.sender = new CustomSender();
    Sender sender = getSenderForServiceFileContents("\nio.jaegertracing.internal.senders.InMemorySenderFactory", true);
    assertTrue(sender instanceof NoopSender);
  }

  @Test
  public void testMultipleImplementationsNotAmbiguous() throws Exception {
    System.setProperty(Configuration.JAEGER_SENDER_FACTORY, "to-be-loaded");
    CustomSender customSender = new CustomSender();
    SenderFactoryToBeLoaded.sender = customSender;
    Sender sender = getSenderForServiceFileContents("\nio.jaegertracing.internal.senders.InMemorySenderFactory", true);
    assertEquals(customSender, sender);

    System.setProperty(Configuration.JAEGER_SENDER_FACTORY, "in-memory");
    sender = getSenderForServiceFileContents("\nio.jaegertracing.internal.senders.InMemorySenderFactory", true);
    assertTrue(sender instanceof InMemorySender);
  }

  @Test
  public void testResolveFromSenderConfiguration() throws Exception {
    Configuration.SenderConfiguration senderConfiguration =
            new Configuration.SenderConfiguration().withSenderFactory("in-memory");
    Sender sender =
            getSenderForServiceFileContents("\nio.jaegertracing.internal.senders.InMemorySenderFactory",
                    true,
                    senderConfiguration);
    assertTrue(sender instanceof InMemorySender);
  }

  @Test
  public void testResolveFromSystemProperty() throws Exception {
    System.setProperty(Configuration.JAEGER_SENDER_FACTORY, "in-memory");
    Configuration.SenderConfiguration senderConfiguration = new Configuration.SenderConfiguration();
    Sender sender =
            getSenderForServiceFileContents("\nio.jaegertracing.internal.senders.InMemorySenderFactory",
                    true,
                    senderConfiguration);
    assertTrue(sender instanceof InMemorySender);
  }

  private Sender getSenderForServiceFileContents(String contents, boolean append) throws Exception {
    return getSenderForServiceFileContents(contents, append, null);
  }

  private Sender getSenderForServiceFileContents(String contents,
                                                 boolean append,
                                                 Configuration.SenderConfiguration configuration) throws Exception {
    String serviceFilePath = "/META-INF/services/io.jaegertracing.spi.SenderFactory";
    File original = new File(this.getClass().getResource(serviceFilePath).toURI());

    String newContent;
    if (append) {
      Scanner s = new Scanner(
          new FileInputStream(original), Charset.defaultCharset().name()
      ).useDelimiter("\\A");
      String originalContents = s.hasNext() ? s.next() : "";
      newContent = originalContents + contents;
    } else {
      newContent = contents;
    }

    Path copy = original.toPath().resolveSibling("io.jaegertracing.spi.SenderFactory-copy");
    Files.move(original.toPath(), copy, REPLACE_EXISTING);

    try {
      FileOutputStream os = new FileOutputStream(original);
      os.write(newContent.getBytes(Charset.defaultCharset()));
      os.close();

      if (configuration != null) {
        return SenderResolver.resolve(configuration);
      }
      return SenderResolver.resolve();
    } finally {
      Files.move(copy, original.toPath(), REPLACE_EXISTING);
    }
  }

  static class CustomSender implements Sender {

    @Override
    public int append(JaegerSpan span) {
      return 0;
    }

    @Override
    public int flush() {
      return 0;
    }

    @Override
    public int close() {
      return 0;
    }

    @Override
    public String toString() {
      return "CustomSender{}";
    }
  }
}
