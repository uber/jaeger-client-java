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
import lombok.ToString;

@SuppressWarnings("EqualsHashCode")
@ToString
public class ConstSampler implements Sampler {
  public static final String TYPE = "const";

  private final boolean decision;

  private final Map<String, Object> tags;

  public ConstSampler(boolean decision) {
    this.decision = decision;
    Map<String, Object> tags = new HashMap<String, Object>();
    tags.put(Constants.SAMPLER_TYPE_TAG_KEY, TYPE);
    tags.put(Constants.SAMPLER_PARAM_TAG_KEY, decision);
    this.tags = Collections.unmodifiableMap(tags);
  }

  /**
   * @param id A long that represents the traceid used to make a sampling decision the command line
   * arguments.
   * @return A boolean that says whether to sample.
   */
  @Override
  public SamplingStatus sample(String operation, long id) {
    return SamplingStatus.of(decision, tags);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other instanceof ConstSampler) {
      return this.decision == ((ConstSampler) other).decision;
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
