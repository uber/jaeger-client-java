/*
 * Copyright (c) 2020, The Jaeger Authors
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

import io.jaegertracing.spi.Sampler;
import io.jaegertracing.spi.TagSampler;

import java.util.HashMap;
import java.util.Map;

public class ProbabilisticTagSampler implements TagSampler {

  private final String tagName;

  private final HashMap<Object, Sampler> tagSamplers;

  private final Sampler defaultSampler;

  public ProbabilisticTagSampler(String tagName, Map<Object, Double> tagRates, double defaultRate) {
    this.tagName = tagName;
    this.defaultSampler = new ProbabilisticSampler(defaultRate);
    this.tagSamplers = new HashMap<Object, Sampler>();
    for (Map.Entry<Object, Double> rate : tagRates.entrySet()) {
      tagSamplers.put(rate.getKey(), new ProbabilisticSampler(rate.getValue()));
    }
  }

  @Override
  public SamplingStatus sample(final String operation, final long id, final Map<String, Object> tags) {
    final Object tagVal = tags.get(tagName);
    Sampler tagSampler;
    if (tagVal == null) {
      tagSampler = defaultSampler;
    } else {
      tagSampler = tagSamplers.get(tagVal);
      if (tagSampler == null) {
        tagSampler = defaultSampler;
      }
    }
    return tagSampler.sample(operation, id);
  }

  @Override
  public SamplingStatus sample(final String operation, final long id) {
    return defaultSampler.sample(operation, id);
  }

  @Override
  public void close() {
    defaultSampler.close();
    for (Map.Entry<Object, Sampler> entry : tagSamplers.entrySet()) {
      entry.getValue().close();
    }
  }
}
