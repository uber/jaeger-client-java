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

import io.jaegertracing.spi.SamplingStatus;
import java.util.Map;
import org.junit.Test;

public class TestRateLimitingSampler {
  @Test
  public void testTags() {
    RateLimitingSampler sampler = new RateLimitingSampler(123);
    Map<String, Object> tags = sampler.sample("operate", 11).getTags();
    assertEquals("ratelimiting", tags.get("sampler.type"));
    assertEquals(123.0, tags.get("sampler.param"));
  }
}
