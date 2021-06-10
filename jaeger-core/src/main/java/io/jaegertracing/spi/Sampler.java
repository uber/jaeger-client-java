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

package io.jaegertracing.spi;

/**
 * Sampler is responsible for deciding if a new trace should be sampled and captured for storage.
 */
public interface Sampler {
  /**
   * @param operation The operation name set on the span
   * @param id The traceId on the span
   * @return whether or not the new trace should be sampled
   */
  SamplingStatus sample(String operation, long id);

  /**
   * Release any resources used by the sampler.
   */
  void close();
}
