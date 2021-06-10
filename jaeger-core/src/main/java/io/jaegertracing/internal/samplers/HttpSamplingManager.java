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

package io.jaegertracing.internal.samplers;

import static io.jaegertracing.internal.utils.Http.makeGetRequest;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import io.jaegertracing.internal.exceptions.SamplingStrategyErrorException;
import io.jaegertracing.internal.samplers.http.SamplingStrategyResponse;
import io.jaegertracing.spi.SamplingManager;
import java.io.IOException;
import java.net.URLEncoder;
import lombok.ToString;


@ToString
public class HttpSamplingManager implements SamplingManager {
  public static final String DEFAULT_SERVER_HOST = "localhost";
  public static final int DEFAULT_SERVER_PORT = 5778;
  public static final String DEFAULT_SERVER_URL =
          "http://" + DEFAULT_SERVER_HOST + ":" + DEFAULT_SERVER_PORT + "/sampling";
  private final String serverUrl;

  @ToString.Exclude private final Gson gson = new Gson();

  /**
   * This constructor expects running sampling manager on {@link #DEFAULT_SERVER_URL}.
   */
  public HttpSamplingManager() {
    this(DEFAULT_SERVER_URL);
  }

  public HttpSamplingManager(String serverUrl) {
    this.serverUrl = serverUrl != null ? serverUrl : DEFAULT_SERVER_URL;
  }

  SamplingStrategyResponse parseJson(String json) {
    try {
      return gson.fromJson(json, SamplingStrategyResponse.class);
    } catch (JsonSyntaxException e) {
      throw new SamplingStrategyErrorException("Cannot deserialize json", e);
    }
  }

  @Override
  public SamplingStrategyResponse getSamplingStrategy(String serviceName)
      throws SamplingStrategyErrorException {
    String jsonString;
    try {
      jsonString =
          makeGetRequest(
              serverUrl + "?service=" + URLEncoder.encode(serviceName, "UTF-8"));
    } catch (IOException e) {
      throw new SamplingStrategyErrorException(
          "http call to get sampling strategy from local agent failed.", e);
    }

    return parseJson(jsonString);
  }
}
