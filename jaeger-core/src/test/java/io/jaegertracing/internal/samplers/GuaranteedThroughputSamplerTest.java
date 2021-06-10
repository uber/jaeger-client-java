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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.jaegertracing.internal.Constants;
import io.jaegertracing.spi.SamplingStatus;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GuaranteedThroughputSamplerTest {

  private GuaranteedThroughputSampler undertest;

  @After
  public void tearDown() {
    undertest.close();
  }

  @Test
  public void testRateLimitingLowerBound() throws Exception {
    undertest = new GuaranteedThroughputSampler(0.0001, 1.0);

    SamplingStatus samplingStatus = undertest.sample("test", Long.MAX_VALUE);
    Assert.assertTrue(samplingStatus.isSampled());
    Map<String, Object> tags = samplingStatus.getTags();

    assertEquals(tags.get(Constants.SAMPLER_TYPE_TAG_KEY), GuaranteedThroughputSampler.TYPE);
    assertEquals(tags.get(Constants.SAMPLER_PARAM_TAG_KEY), 0.0001);
  }

  @Test
  public void testProbabilityTagsOverrideRateLimitingTags() throws Exception {
    undertest = new GuaranteedThroughputSampler(0.999, 1.0);

    SamplingStatus samplingStatus = undertest.sample("test", 0L);
    Assert.assertTrue(samplingStatus.isSampled());
    Map<String, Object> tags = samplingStatus.getTags();

    assertEquals(tags.get(Constants.SAMPLER_TYPE_TAG_KEY), ProbabilisticSampler.TYPE);
    assertEquals(tags.get(Constants.SAMPLER_PARAM_TAG_KEY), 0.999);
  }

  @Test
  public void testUpdate_probabilisticSampler() {
    undertest = new GuaranteedThroughputSampler(0.001, 1);

    assertFalse(undertest.update(0.001, 1));
    assertTrue(undertest.update(0.002, 1));

    SamplingStatus samplingStatus = undertest.sample("test", Long.MAX_VALUE);
    Assert.assertTrue(samplingStatus.isSampled());
    Map<String, Object> tags = samplingStatus.getTags();

    assertEquals(tags.get(Constants.SAMPLER_TYPE_TAG_KEY), GuaranteedThroughputSampler.TYPE);
    assertEquals(tags.get(Constants.SAMPLER_PARAM_TAG_KEY), 0.002);
  }

  @Test
  public void testUpdate_rateLimitingSampler() {
    undertest = new GuaranteedThroughputSampler(0.001, 1);

    assertFalse(undertest.update(0.001, 1));
    assertTrue(undertest.update(0.001, 0));

    SamplingStatus samplingStatus = undertest.sample("test", 0L);
    Assert.assertTrue(samplingStatus.isSampled());
    Map<String, Object> tags = samplingStatus.getTags();

    assertEquals(tags.get(Constants.SAMPLER_TYPE_TAG_KEY), ProbabilisticSampler.TYPE);
    assertEquals(tags.get(Constants.SAMPLER_PARAM_TAG_KEY), 0.001);
  }
}
