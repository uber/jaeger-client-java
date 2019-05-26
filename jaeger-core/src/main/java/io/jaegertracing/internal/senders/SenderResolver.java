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

import io.jaegertracing.Configuration;
import io.jaegertracing.spi.Sender;
import io.jaegertracing.spi.SenderFactory;
import java.util.Iterator;
import java.util.ServiceLoader;
import lombok.extern.slf4j.Slf4j;

/**
 * Provides a way to resolve an appropriate {@link Sender}
 */
@Slf4j
public class SenderResolver {

  /**
   * Resolves a {@link Sender} by passing {@link Configuration.SenderConfiguration#fromEnv()} down to the
   * {@link SenderFactory}
   *
   * @see #resolve(Configuration.SenderConfiguration)
   * @return the resolved Sender, or NoopSender
   */
  public static Sender resolve() {
    return resolve(Configuration.SenderConfiguration.fromEnv());
  }

  /**
   * Resolves a sender by passing the given {@link Configuration.SenderConfiguration} down to the
   * {@link SenderFactory}. The factory is loaded either based on the value from sender configuration
   * {@link Configuration.SenderConfiguration#senderFactory},
   * or the environment variable {@link Configuration#JAEGER_SENDER_FACTORY},
   * or, in its absence or failure to deliver a {@link Sender},
   * via the {@link ServiceLoader}. If no factories are found, a {@link NoopSender} is returned. If multiple factories
   * are available, the factory whose {@link SenderFactory#getType()} matches the
   * {@link Configuration.SenderConfiguration#senderFactory} value (or the JAEGER_SENDER_FACTORY env var) is
   * selected. If none matches, {@link NoopSender} is returned.
   * Using {@link Configuration#JAEGER_SENDER_FACTORY} is deprecated in favor of
   * {@link Configuration.SenderConfiguration#senderFactory}
   *
   * @param senderConfiguration the configuration to pass down to the factory
   * @return the resolved Sender, or NoopSender
   */
  public static Sender resolve(Configuration.SenderConfiguration senderConfiguration) {
    Sender sender = null;
    ServiceLoader<SenderFactory> senderFactoryServiceLoader = ServiceLoader.load(SenderFactory.class,
        SenderFactory.class.getClassLoader());
    Iterator<SenderFactory> senderFactoryIterator = senderFactoryServiceLoader.iterator();

    boolean hasMultipleFactories = false;
    while (senderFactoryIterator.hasNext()) {
      SenderFactory senderFactory = senderFactoryIterator.next();

      if (senderFactoryIterator.hasNext()) {
        log.debug("There are multiple factories available via the service loader.");
        hasMultipleFactories = true;
      }

      if (hasMultipleFactories) {
        // we compare the factory name with the requested configuration, as a way to know which
        // factory the user wants:
        String requestedFactory = senderConfiguration.getSenderFactory();
        if (requestedFactory == null) {
          /* backward compatibility */
          requestedFactory = System.getProperty(Configuration.JAEGER_SENDER_FACTORY);
        }
        if (senderFactory.getType().equals(requestedFactory)) {
          log.debug(
              String.format("Found the requested (%s) sender factory: %s",
                  requestedFactory,
                  senderFactory)
          );

          sender = getSenderFromFactory(senderFactory, senderConfiguration);
        }
      } else {
        sender = getSenderFromFactory(senderFactory, senderConfiguration);
      }
    }

    if (null != sender) {
      log.debug(String.format("Using sender %s", sender));
      return sender;
    } else {
      log.warn("No suitable sender found. Using NoopSender, meaning that data will not be sent anywhere!");
      return new NoopSender();
    }
  }

  private static Sender getSenderFromFactory(SenderFactory senderFactory,
                                             Configuration.SenderConfiguration configuration) {
    try {
      return senderFactory.getSender(configuration);
    } catch (Exception e) {
      log.warn("Failed to get a sender from the sender factory.", e);
      return null;
    }
  }
}
