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

package io.jaegertracing.thrift.internal.senders;

import io.jaegertracing.internal.exceptions.SenderException;
import io.jaegertracing.thriftjava.Batch;
import io.jaegertracing.thriftjava.Process;
import io.jaegertracing.thriftjava.Span;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.security.NoSuchAlgorithmException;
import java.security.KeyManagementException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.TrustManager;
import lombok.ToString;
import okhttp3.CertificatePinner;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@ToString
public class HttpSender extends ThriftSender {
  private static final String HTTP_COLLECTOR_JAEGER_THRIFT_FORMAT_PARAM = "format=jaeger.thrift";
  private static final int ONE_MB_IN_BYTES = 1048576;
  private static final MediaType MEDIA_TYPE_THRIFT = MediaType.parse("application/x-thrift");

  @ToString.Exclude private final OkHttpClient httpClient;
  @ToString.Exclude private final Request.Builder requestBuilder;

  protected HttpSender(Builder builder) {
    super(ProtocolType.Binary, builder.maxPacketSize);
    HttpUrl collectorUrl = HttpUrl
        .parse(String.format("%s?%s", builder.endpoint, HTTP_COLLECTOR_JAEGER_THRIFT_FORMAT_PARAM));
    if (collectorUrl == null) {
      throw new IllegalArgumentException("Could not parse url.");
    }

    this.httpClient = builder.clientBuilder.build();
    this.requestBuilder = new Request.Builder().url(collectorUrl);
  }

  @Override
  public void send(Process process, List<Span> spans) throws SenderException {
    Batch batch = new Batch(process, spans);
    byte[] bytes = null;
    try {
      bytes = serialize(batch);
    } catch (Exception e) {
      throw new SenderException(String.format("Failed to serialize %d spans", spans.size()), e, spans.size());
    }

    RequestBody body = RequestBody.create(MEDIA_TYPE_THRIFT, bytes);
    Request request = requestBuilder.post(body).build();
    Response response;
    try {
      response = httpClient.newCall(request).execute();
    } catch (IOException e) {
      throw new SenderException(String.format("Could not send %d spans", spans.size()), e, spans.size());
    }

    if (response.isSuccessful()) {
      response.close();
      return;
    }

    String responseBody;
    try {
      responseBody = response.body() != null ? response.body().string() : "null";
    } catch (IOException e) {
      responseBody = "unable to read response";
    }

    String exceptionMessage = String.format("Could not send %d spans, response %d: %s",
        spans.size(), response.code(), responseBody);

    throw new SenderException(exceptionMessage, null, spans.size());
  }

  public static class Builder {
    private final String endpoint;
    private CertificatePinner.Builder certificatePinnerBuilder = new CertificatePinner.Builder();
    private int maxPacketSize = ONE_MB_IN_BYTES;
    private Interceptor authInterceptor;
    private OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
    private List<String> pins = new ArrayList<String>();
    private boolean selfSigned = false;

    /**
     * @param endpoint jaeger-collector HTTP endpoint e.g. http://localhost:14268/api/traces
     */
    public Builder(String endpoint) {
      this.endpoint = endpoint;
    }

    public Builder withClient(OkHttpClient client) {
      this.clientBuilder = client.newBuilder();
      return this;
    }

    public Builder withMaxPacketSize(int maxPacketSizeBytes) {
      this.maxPacketSize = maxPacketSizeBytes;
      return this;
    }

    public Builder withAuth(String username, String password) {
      this.authInterceptor = getAuthInterceptor(Credentials.basic(username, password));
      return this;
    }

    public Builder withAuth(String authToken) {
      this.authInterceptor = getAuthInterceptor("Bearer " + authToken);
      return this;
    }

    public Builder withCertificatePinning(String[] sha256certs) {
      pins.addAll(Arrays.asList(sha256certs));
      return this;
    }

    /**
     * Enable accepting self-signed certificates. This will only take effect if pinning is provided.
     */
    public Builder acceptSelfSigned() {
      this.selfSigned = true;
      return this;
    }

    public HttpSender build() {
      if (authInterceptor != null) {
        clientBuilder.addInterceptor(authInterceptor);
      }
      try {
        // Just obtain hostname for SSL feature. Failure is OK if plain http is used.
        final URI uri = new URI(endpoint);
        String hostname = hostname = uri.getHost();

        if ("https".equals(uri.getScheme())) {
          if (!selfSigned && !pins.isEmpty()) {
            // Pinning Certificate issued by public CA
            for (String cert: pins) {
              certificatePinnerBuilder.add(hostname, String.format("%s", cert));
            }
            clientBuilder.certificatePinner(certificatePinnerBuilder.build());
          } else if (selfSigned && !pins.isEmpty()) {
            /* Issued by private CA, OkHttp's pinner is unable to verify the pins.
             * Instead, check the sha256 hash value by custom verifier. */
            acceptSelfSigned(clientBuilder, hostname, pins);
          }
        }
      } catch (java.net.URISyntaxException e) {
        // Early but similar validation & exception as what happens when HttpSender is called.
        throw new IllegalArgumentException("Could not parse endpoint.", e);
      }
      return new HttpSender(this);
    }

    private void acceptSelfSigned(OkHttpClient.Builder clientBuilder, final String hostname, final List<String> pinlist) {
      try {
        final TrustManager[] selfSignedServerTrustManager = new TrustManager[] { new SelfSignedTrustManager(hostname, pinlist) };
        final SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, selfSignedServerTrustManager, null);
        clientBuilder.sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) selfSignedServerTrustManager[0]);

      } catch (NoSuchAlgorithmException e) {
          // TLS is hardcoded above. No occasion to come here.
          throw new RuntimeException(e);
      } catch (KeyManagementException e) {
          /* KeyManagementException will not occurs because sslContext uses default KeyManager (first argument).
           *
           * > Either of the first two parameters may be null in which case the installed security providers
           * > will be searched for the highest priority implementation of the appropriate factory.
           */
          throw new RuntimeException(e);
      }
    }

    private Interceptor getAuthInterceptor(final String headerValue) {
      return new Interceptor() {
        @Override
        public Response intercept(Chain chain) throws IOException {
          return chain.proceed(
              chain.request()
                  .newBuilder()
                  .addHeader("Authorization", headerValue)
                  .build()
          );
        }
      };
    }

    private static class SelfSignedTrustManager implements X509TrustManager {
      private final String subjectCN;
      private final List<String> pins;

      protected SelfSignedTrustManager(String hostname, List<String> pins) {
          this.subjectCN = "CN=" + hostname;
          this.pins = pins;
      }

      private boolean check(X509Certificate[] chain) {
        // Intersection of pins and every cert in this chain
        for (X509Certificate cert: chain) {
          String hash = CertificatePinner.pin(cert);
          for (String pin: pins) {
            if (hash.equals(pin)) {
              return true;
            }
          }
        }
        return false;
      }

      private String describePins(X509Certificate[] chain) {
          String message = "No pins matched with remote certificate chain. The pins are:";
          for (X509Certificate cert: chain) {
              message = message + "\n\t" + CertificatePinner.pin(cert);
          }
          return message + "\n";
      }

      public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        for (X509Certificate cert: chain) {
          String[] subject = cert.getSubjectDN().getName().split("/");
          for (String name: subject) {
            if (subjectCN.equals(name)) {
              // Found endpoint's hostname. This chain is going to be tested.
              if (check(chain)) {
                return;
              } else {
                // For TOFU (trust on first use) usecase, print the chain
                throw new RuntimeException(describePins(chain));
              }
            }
          }
        }
        throw new CertificateException();
      }
      public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException(); // Nothing will be accepted
      }
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    }
  }
}
