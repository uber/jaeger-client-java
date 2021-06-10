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

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.jaegertracing.spi.Sampler;
import io.jaegertracing.spi.SamplingStatus;
import java.util.Map;
import org.junit.Test;

public class ProbabilisticSamplerTest {

  @Test
  public void testSamplingBoundariesPositive() {
    double samplingRate = 0.5;

    long halfwayBoundary = 0x3fffffffffffffffL;
    Sampler sampler = new ProbabilisticSampler(samplingRate);
    assertTrue(sampler.sample("", halfwayBoundary).isSampled());

    assertFalse(sampler.sample("", halfwayBoundary + 2).isSampled());
  }

  @Test
  public void testSamplingBoundariesNegative() {
    double samplingRate = 0.5;

    long halfwayBoundary = -0x4000000000000000L;
    Sampler sampler = new ProbabilisticSampler(samplingRate);
    assertTrue(sampler.sample("", halfwayBoundary).isSampled());

    assertFalse(sampler.sample("", halfwayBoundary - 1).isSampled());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testSamplerThrowsInvalidSamplingRangeExceptionUnder() {
    new ProbabilisticSampler(-0.1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testSamplerThrowsInvalidSamplingRangeExceptionOver() {
    new ProbabilisticSampler(1.1);
  }

  @Test
  public void testTags() {
    ProbabilisticSampler sampler = new ProbabilisticSampler(0.1);
    Map<String, Object> tags = sampler.sample("vadacurry", 20L).getTags();
    assertEquals("probabilistic", tags.get("sampler.type"));
    assertEquals(0.1, tags.get("sampler.param"));
  }
}
