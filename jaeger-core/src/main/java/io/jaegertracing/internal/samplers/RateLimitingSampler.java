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

import io.jaegertracing.internal.Constants;
import io.jaegertracing.internal.utils.RateLimiter;
import io.jaegertracing.spi.Sampler;
import io.jaegertracing.spi.SamplingStatus;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.ToString;

@SuppressWarnings("EqualsHashCode")
@ToString
public class RateLimitingSampler implements Sampler {
  public static final String TYPE = "ratelimiting";

  @Getter
  private final double maxTracesPerSecond;
  private final Map<String, Object> tags;

  @ToString.Exclude private final RateLimiter rateLimiter;

  public RateLimitingSampler(double maxTracesPerSecond) {
    this.maxTracesPerSecond = maxTracesPerSecond;
    double maxBalance = maxTracesPerSecond < 1.0 ? 1.0 : maxTracesPerSecond;
    this.rateLimiter = new RateLimiter(maxTracesPerSecond, maxBalance);

    Map<String, Object> tags = new HashMap<String, Object>();
    tags.put(Constants.SAMPLER_TYPE_TAG_KEY, TYPE);
    tags.put(Constants.SAMPLER_PARAM_TAG_KEY, maxTracesPerSecond);
    this.tags = Collections.unmodifiableMap(tags);
  }

  @Override
  public SamplingStatus sample(String operation, long id) {
    return SamplingStatus.of(this.rateLimiter.checkCredit(1.0), tags);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof RateLimitingSampler) {
      return this.maxTracesPerSecond == ((RateLimitingSampler) other).maxTracesPerSecond;
    }
    return false;
  }

  /**
   * Only implemented to maintain compatibility with sampling interface.
   */
  @Override
  public void close() {}
}
