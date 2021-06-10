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

import java.util.Map;

public class TagSamplerWrapper implements TagSampler {

  private final Sampler baseSampler;

  public TagSamplerWrapper(Sampler baseSampler) {
    this.baseSampler = baseSampler;
  }

  public Sampler getBaseSampler() {
    return this.baseSampler;
  }

  @Override
  public SamplingStatus sample(final String operation, final long id) {
    return baseSampler.sample(operation, id);
  }

  @Override
  public SamplingStatus sample(final String operation, final long id, final Map<String, Object> tags) {
    return baseSampler.sample(operation, id);
  }

  @Override
  public void close() {
    baseSampler.close();
  }
}
