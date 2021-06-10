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
import io.jaegertracing.spi.Sampler;
import io.jaegertracing.spi.SamplingStatus;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.ToString;

@SuppressWarnings("EqualsHashCode")
@ToString
public class ProbabilisticSampler implements Sampler {
  public static final double DEFAULT_SAMPLING_PROBABILITY = 0.001;
  public static final String TYPE = "probabilistic";

  private final Map<String, Object> tags;

  // exclude from toString as these are represented in the tags map already
  @ToString.Exclude private final long positiveSamplingBoundary;
  @ToString.Exclude private final long negativeSamplingBoundary;
  @ToString.Exclude @Getter private final double samplingRate;

  public ProbabilisticSampler(double samplingRate) {
    if (samplingRate < 0.0 || samplingRate > 1.0) {
      throw new IllegalArgumentException(
          "The sampling rate must be greater than 0.0 and less than 1.0");
    }

    this.samplingRate = samplingRate;
    this.positiveSamplingBoundary = (long) (((1L << 63) - 1) * samplingRate);
    this.negativeSamplingBoundary = (long) ((1L << 63) * samplingRate);

    Map<String, Object> tags = new HashMap<String, Object>();
    tags.put(Constants.SAMPLER_TYPE_TAG_KEY, TYPE);
    tags.put(Constants.SAMPLER_PARAM_TAG_KEY, samplingRate);
    this.tags = Collections.unmodifiableMap(tags);
  }

  /**
   * Uses a trace id to make a sampling decision.
   *
   * @param id A long that represents the traceid used to make a sampling decision
   * @return A boolean that says wheter or not to sample.
   */
  @Override
  public SamplingStatus sample(String operation, long id) {
    if (id > 0) {
      return SamplingStatus.of(id <= this.positiveSamplingBoundary, tags);
    } else {
      return SamplingStatus.of(id >= this.negativeSamplingBoundary, tags);
    }
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof ProbabilisticSampler) {
      return this.samplingRate == ((ProbabilisticSampler) other).samplingRate;
    }
    return false;
  }

  /**
   * Only implemented to satisfy the sampler interface
   */
  @Override
  public void close() {
    // nothing to do
  }
}
