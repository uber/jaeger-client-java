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
import java.util.HashMap;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * {@link GuaranteedThroughputSampler} is a {@link Sampler} that guarantees a throughput by using
 * a {@link ProbabilisticSampler} and {@link RateLimitingSampler} in tandem.
 *
 * The RateLimitingSampler is used to establish a lowerBound so that every operation is sampled
 * at least once in the time interval defined by the lowerBound.
 */
@ToString
@EqualsAndHashCode
public final class GuaranteedThroughputSampler implements Sampler {
  public static final String TYPE = "lowerbound";

  private ProbabilisticSampler probabilisticSampler;
  private RateLimitingSampler lowerBoundSampler;
  private Map<String, Object> tags;

  public GuaranteedThroughputSampler(double samplingRate, double lowerBound) {
    tags = new HashMap<String, Object>();
    tags.put(Constants.SAMPLER_TYPE_TAG_KEY, TYPE);
    tags.put(Constants.SAMPLER_PARAM_TAG_KEY, samplingRate);

    probabilisticSampler = new ProbabilisticSampler(samplingRate);
    lowerBoundSampler = new RateLimitingSampler(lowerBound);
  }

  /**
   * Updates the probabilistic and lowerBound samplers
   * @param samplingRate The sampling rate for probabilistic sampling
   * @param lowerBound The lower bound limit for lower bound sampling
   * @return true iff any samplers were updated
   */
  public synchronized boolean update(double samplingRate, double lowerBound) {
    boolean isUpdated = false;
    if (samplingRate != probabilisticSampler.getSamplingRate()) {
      probabilisticSampler = new ProbabilisticSampler(samplingRate);
      tags.put(Constants.SAMPLER_PARAM_TAG_KEY, samplingRate);
      isUpdated = true;
    }
    if (lowerBound != lowerBoundSampler.getMaxTracesPerSecond()) {
      lowerBoundSampler = new RateLimitingSampler(lowerBound);
      isUpdated = true;
    }
    return isUpdated;
  }

  /**
   * Calls {@link Sampler#sample(String, long)} (String, long)} on both samplers, returning true for
   * {@link SamplingStatus#isSampled} if either samplers set #isSampled to true.
   * The tags corresponding to the sampler that returned true are set on {@link SamplingStatus#tags}
   * If both samplers return true, tags for {@link ProbabilisticSampler} is given priority.
   * @param operation The operation name, which is ignored by this sampler
   * @param id The traceId on the span
   */
  @Override
  public synchronized SamplingStatus sample(String operation, long id) {
    SamplingStatus probabilisticSamplingStatus = probabilisticSampler.sample(operation, id);
    SamplingStatus lowerBoundSamplingStatus = lowerBoundSampler.sample(operation, id);

    if (probabilisticSamplingStatus.isSampled()) {
      return probabilisticSamplingStatus;
    }

    return SamplingStatus.of(lowerBoundSamplingStatus.isSampled(), tags);
  }

  @Override
  public synchronized void close() {
    probabilisticSampler.close();
    lowerBoundSampler.close();
  }
}
