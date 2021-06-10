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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import io.jaegertracing.internal.exceptions.SamplingStrategyErrorException;
import io.jaegertracing.internal.samplers.http.OperationSamplingParameters;
import io.jaegertracing.internal.samplers.http.PerOperationSamplingParameters;
import io.jaegertracing.internal.samplers.http.ProbabilisticSamplingStrategy;
import io.jaegertracing.internal.samplers.http.RateLimitingSamplingStrategy;
import io.jaegertracing.internal.samplers.http.SamplingStrategyResponse;
import io.jaegertracing.mocks.MockAgentResource;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;
import javax.ws.rs.core.Application;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class HttpSamplingManagerTest extends JerseyTest {

  HttpSamplingManager undertest = new HttpSamplingManager(null);

  private static Properties originalProps;

  @BeforeClass
  public static void beforeClass() {
    originalProps = new Properties(System.getProperties());
    if (System.getProperty(TestProperties.CONTAINER_PORT) == null) {
      System.setProperty(TestProperties.CONTAINER_PORT, "0");
    }
  }

  @AfterClass
  public static void afterClass() {
    System.setProperties(originalProps);
  }

  @Override
  protected Application configure() {
    return new ResourceConfig(MockAgentResource.class);
  }

  @Test
  public void testGetSamplingStrategy() throws Exception {
    URI uri = target().getUri();
    undertest = new HttpSamplingManager("http://" + uri.getHost() + ":" + uri.getPort());
    SamplingStrategyResponse response = undertest.getSamplingStrategy("clairvoyant");
    assertNotNull(response.getProbabilisticSampling());
  }

  @Test (expected = SamplingStrategyErrorException.class)
  public void testGetSamplingStrategyError() throws Exception {
    URI uri = target().getUri();
    undertest = new HttpSamplingManager(uri.getHost() + ":" + uri.getPort());
    undertest.getSamplingStrategy("");
  }

  @Test
  public void testParseProbabilisticSampling() throws Exception {
    SamplingStrategyResponse response =
        undertest.parseJson(readFixture("probabilistic_sampling.json"));
    assertEquals(new ProbabilisticSamplingStrategy(0.01), response.getProbabilisticSampling());
    assertNull(response.getRateLimitingSampling());
  }

  @Test
  public void testParseRateLimitingSampling() throws Exception {
    SamplingStrategyResponse response =
        undertest.parseJson(readFixture("ratelimiting_sampling.json"));
    assertEquals(new RateLimitingSamplingStrategy(2.1), response.getRateLimitingSampling());
    assertNull(response.getProbabilisticSampling());
  }

  @Test (expected = SamplingStrategyErrorException.class)
  public void testParseInvalidJson() throws Exception {
    undertest.parseJson("invalid json");
  }

  @Test
  public void testParsePerOperationSampling() throws Exception {
    SamplingStrategyResponse response =
        undertest.parseJson(readFixture("per_operation_sampling.json"));
    OperationSamplingParameters actual = response.getOperationSampling();
    assertEquals(0.001, actual.getDefaultSamplingProbability(), 0.0001);
    assertEquals(0.001666, actual.getDefaultLowerBoundTracesPerSecond(),0.0001);

    List<PerOperationSamplingParameters> actualPerOperationStrategies = actual.getPerOperationStrategies();
    assertEquals(2, actualPerOperationStrategies.size());
    assertEquals(
        new PerOperationSamplingParameters("GET:/search", new ProbabilisticSamplingStrategy(1.0)),
        actualPerOperationStrategies.get(0));
    assertEquals(
        new PerOperationSamplingParameters("PUT:/pacifique", new ProbabilisticSamplingStrategy(0.8258308134813166)),
        actualPerOperationStrategies.get(1));
  }

  @Test(expected = SamplingStrategyErrorException.class)
  public void testDefaultConstructor() {
    HttpSamplingManager httpSamplingManager = new HttpSamplingManager();
    httpSamplingManager.getSamplingStrategy("name");
  }

  private String readFixture(String fixtureName) throws Exception {
    URL resource = getClass().getClassLoader().getResource(fixtureName);
    File file = new File(resource.toURI());
    return new String(Files.readAllBytes(file.toPath()), Charset.defaultCharset());
  }

}
