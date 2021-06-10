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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.jaegertracing.internal.samplers.http.OperationSamplingParameters;
import io.jaegertracing.internal.samplers.http.PerOperationSamplingParameters;
import io.jaegertracing.internal.samplers.http.ProbabilisticSamplingStrategy;
import io.jaegertracing.spi.SamplingStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class PerOperationSamplerTest {

  private static final double SAMPLING_RATE = 0.31415;
  private static final double DEFAULT_SAMPLING_PROBABILITY = 0.512;
  private static final double DEFAULT_LOWER_BOUND_TRACES_PER_SECOND = 2.0;
  private static final int MAX_OPERATIONS = 100;
  private static final double DELTA = 0.001;
  private static final long TRACE_ID = 1L;
  private static final String OPERATION = "some OPERATION";

  @Mock private ProbabilisticSampler defaultProbabilisticSampler;
  private HashMap<String, GuaranteedThroughputSampler> operationToSamplers = new HashMap<>();
  private PerOperationSampler undertest;

  @Before
  public void setUp() {
    undertest = new PerOperationSampler(MAX_OPERATIONS, operationToSamplers, defaultProbabilisticSampler,
        DEFAULT_LOWER_BOUND_TRACES_PER_SECOND);
    when(defaultProbabilisticSampler.sample(OPERATION, TRACE_ID)).thenReturn(
        SamplingStatus.of(true, new HashMap<String, Object>()));
    when(defaultProbabilisticSampler.getSamplingRate()).thenReturn(DEFAULT_SAMPLING_PROBABILITY);
  }

  @After
  public void tearDown() {
    undertest.close();
  }

  @Test
  public void testFallbackToDefaultProbabilisticSampler() {
    undertest = new PerOperationSampler(0, operationToSamplers, defaultProbabilisticSampler,
        DEFAULT_LOWER_BOUND_TRACES_PER_SECOND);
    SamplingStatus samplingStatus = undertest.sample(OPERATION, TRACE_ID);
    assertTrue(samplingStatus.isSampled());
    verify(defaultProbabilisticSampler).sample(OPERATION, TRACE_ID);
  }

  @Test
  public void testCreateGuaranteedSamplerOnUnseenOperation() {
    String newOperation = "new OPERATION";
    undertest.sample(newOperation, TRACE_ID);
    assertEquals(new GuaranteedThroughputSampler(DEFAULT_SAMPLING_PROBABILITY,
                                                 DEFAULT_LOWER_BOUND_TRACES_PER_SECOND),
                 operationToSamplers.get(newOperation));
  }

  @Test
  public void testPerOperationSamplerWithKnownOperation() {
    GuaranteedThroughputSampler sampler = mock(GuaranteedThroughputSampler.class);
    operationToSamplers.put(OPERATION, sampler);

    when(sampler.sample(OPERATION, TRACE_ID)).thenReturn(SamplingStatus.of(true, new HashMap<String, Object>()));

    SamplingStatus samplingStatus = undertest.sample(OPERATION, TRACE_ID);
    assertTrue(samplingStatus.isSampled());
    verify(sampler).sample(OPERATION, TRACE_ID);
    verifyNoMoreInteractions(defaultProbabilisticSampler);
  }

  @Test
  public void testUpdate() {
    GuaranteedThroughputSampler guaranteedThroughputSampler = mock(GuaranteedThroughputSampler.class);
    operationToSamplers.put(OPERATION, guaranteedThroughputSampler);

    PerOperationSamplingParameters perOperationSamplingParameters =
        new PerOperationSamplingParameters(OPERATION, new ProbabilisticSamplingStrategy(SAMPLING_RATE));
    List<PerOperationSamplingParameters> parametersList = new ArrayList<>();
    parametersList.add(perOperationSamplingParameters);

    OperationSamplingParameters parameters =
        new OperationSamplingParameters(DEFAULT_SAMPLING_PROBABILITY,
            DEFAULT_LOWER_BOUND_TRACES_PER_SECOND, parametersList);

    assertTrue(undertest.update(parameters));
    verify(guaranteedThroughputSampler).update(SAMPLING_RATE, DEFAULT_LOWER_BOUND_TRACES_PER_SECOND);
    verifyNoMoreInteractions(guaranteedThroughputSampler);
  }

  @Test
  public void testNoopUpdate() {
    ProbabilisticSampler defaultProbabilisticSampler = new ProbabilisticSampler(DEFAULT_SAMPLING_PROBABILITY);
    double operationSamplingRate = 0.23;
    operationToSamplers.put(OPERATION, new GuaranteedThroughputSampler(operationSamplingRate,
        DEFAULT_LOWER_BOUND_TRACES_PER_SECOND));
    undertest = new PerOperationSampler(MAX_OPERATIONS, operationToSamplers, defaultProbabilisticSampler,
        DEFAULT_LOWER_BOUND_TRACES_PER_SECOND);

    List<PerOperationSamplingParameters> parametersList = new ArrayList<>();
    parametersList.add(new PerOperationSamplingParameters(OPERATION,
        new ProbabilisticSamplingStrategy(operationSamplingRate)));
    OperationSamplingParameters parameters = new OperationSamplingParameters(DEFAULT_SAMPLING_PROBABILITY,
        DEFAULT_LOWER_BOUND_TRACES_PER_SECOND, parametersList);

    assertFalse(undertest.update(parameters));
    assertEquals(operationToSamplers, undertest.getOperationNameToSampler());
    assertEquals(DEFAULT_LOWER_BOUND_TRACES_PER_SECOND, undertest.getLowerBound(), DELTA);
    assertEquals(DEFAULT_SAMPLING_PROBABILITY, undertest.getDefaultSampler().getSamplingRate(), DELTA);
  }

  @Test
  public void testUpdateIgnoreGreaterThanMax() {
    GuaranteedThroughputSampler guaranteedThroughputSampler = mock(GuaranteedThroughputSampler.class);
    operationToSamplers.put(OPERATION, guaranteedThroughputSampler);

    PerOperationSampler undertest = new PerOperationSampler(1, operationToSamplers,
        defaultProbabilisticSampler, DEFAULT_LOWER_BOUND_TRACES_PER_SECOND);
    PerOperationSamplingParameters perOperationSamplingParameters1 =
        new PerOperationSamplingParameters(OPERATION, new ProbabilisticSamplingStrategy(SAMPLING_RATE));
    PerOperationSamplingParameters perOperationSamplingParameters2 =
        new PerOperationSamplingParameters("second OPERATION", new ProbabilisticSamplingStrategy(SAMPLING_RATE));
    List<PerOperationSamplingParameters> parametersList = new ArrayList<>();
    parametersList.add(perOperationSamplingParameters1);
    parametersList.add(perOperationSamplingParameters2);

    undertest.update(new OperationSamplingParameters(DEFAULT_SAMPLING_PROBABILITY,
                                                     DEFAULT_LOWER_BOUND_TRACES_PER_SECOND, parametersList));

    assertEquals(1, undertest.getOperationNameToSampler().size());
    assertNotNull(undertest.getOperationNameToSampler().get(OPERATION));
  }

  @Test
  public void testUpdateAddOperation() {
    PerOperationSamplingParameters perOperationSamplingParameters1 =
        new PerOperationSamplingParameters(OPERATION, new ProbabilisticSamplingStrategy(SAMPLING_RATE));
    List<PerOperationSamplingParameters> parametersList = new ArrayList<>();
    parametersList.add(perOperationSamplingParameters1);

    undertest.update(new OperationSamplingParameters(DEFAULT_SAMPLING_PROBABILITY,
                                                     DEFAULT_LOWER_BOUND_TRACES_PER_SECOND,
                                                     parametersList));

    assertEquals(1, undertest.getOperationNameToSampler().size());
    assertEquals(new GuaranteedThroughputSampler(SAMPLING_RATE,
                                                 DEFAULT_LOWER_BOUND_TRACES_PER_SECOND),
            undertest.getOperationNameToSampler().get(OPERATION));
  }

  @Test
  public void testAbsentOperationIsRemoved() {
    String absentOp = "ShouldBeRemoved";
    operationToSamplers.put(absentOp, mock(GuaranteedThroughputSampler.class));
    PerOperationSamplingParameters perOperationSamplingParameters1 =
            new PerOperationSamplingParameters(OPERATION, new ProbabilisticSamplingStrategy(SAMPLING_RATE));
    List<PerOperationSamplingParameters> parametersList = new ArrayList<>();
    parametersList.add(perOperationSamplingParameters1);

    undertest.update(new OperationSamplingParameters(DEFAULT_SAMPLING_PROBABILITY,
            DEFAULT_LOWER_BOUND_TRACES_PER_SECOND,
            parametersList));

    assertEquals(1, undertest.getOperationNameToSampler().size());
    assertEquals(new GuaranteedThroughputSampler(SAMPLING_RATE,
                    DEFAULT_LOWER_BOUND_TRACES_PER_SECOND),
            undertest.getOperationNameToSampler().get(OPERATION));
    assertFalse(undertest.getOperationNameToSampler().containsKey(absentOp));
  }
}
