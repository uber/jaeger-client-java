package io.jaegertracing.thrift.internal.senders;

import io.jaegertracing.Configuration;
import io.jaegertracing.spi.Sender;
import io.jaegertracing.spi.SenderFactory;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * Factory for {@link Sender} instances backed by Thrift. The actual sender implementation can be either
 * {@link HttpSender} or {@link UdpSender}, based on the given {@link Configuration.SenderConfiguration}.
 */
@Slf4j
@ToString
public class ThriftSenderFactory implements SenderFactory {
  @Override
  public Sender getSender(Configuration.SenderConfiguration conf) {
    if (null != conf.getEndpoint() && !conf.getEndpoint().isEmpty()) {
      HttpSender.Builder httpSenderBuilder = new HttpSender.Builder(conf.getEndpoint());
      if (null != conf.getAuthUsername() && !conf.getAuthUsername().isEmpty()
          && null != conf.getAuthPassword() && !conf.getAuthPassword().isEmpty()) {
        log.debug("Using HTTP Basic authentication with data from the environment variables.");
        httpSenderBuilder.withAuth(conf.getAuthUsername(), conf.getAuthPassword());
      } else if (null != conf.getAuthToken() && !conf.getAuthToken().isEmpty()) {
        log.debug("Auth Token environment variable found.");
        httpSenderBuilder.withAuth(conf.getAuthToken());
      }

      if (null != conf.getServerCertificateHashes() && 0 != conf.getServerCertificateHashes().length) {
        httpSenderBuilder.withCertificatePinning(conf.getServerCertificateHashes());
      }

      log.debug("Using the HTTP Sender to send spans directly to the endpoint.");
      return httpSenderBuilder.build();
    }

    log.debug("Using the UDP Sender to send spans to the agent.");
    return new UdpSender(
        stringOrDefault(conf.getAgentHost(), UdpSender.DEFAULT_AGENT_UDP_HOST),
        numberOrDefault(conf.getAgentPort(), UdpSender.DEFAULT_AGENT_UDP_COMPACT_PORT).intValue(),
        0 /* max packet size */);
  }

  @Override
  public String getType() {
    return "thrift";
  }

  private static String stringOrDefault(String value, String defaultValue) {
    return value != null && value.length() > 0 ? value : defaultValue;
  }

  private static Number numberOrDefault(Number value, Number defaultValue) {
    return value != null ? value : defaultValue;
  }
}
