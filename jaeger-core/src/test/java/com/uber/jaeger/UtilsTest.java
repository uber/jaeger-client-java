/*
 * Copyright (c) 2016, Uber Technologies, Inc
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.jaeger;

import static org.junit.Assert.assertEquals;

import com.uber.jaeger.exceptions.EmptyIpException;
import com.uber.jaeger.exceptions.NotFourOctetsException;
import com.uber.jaeger.utils.Utils;
import org.junit.Test;

public class UtilsTest {

  @Test
  public void testNormalizeBaggageKey() {
    String expectedKey = "test-key";
    String actualKey = Utils.normalizeBaggageKey("TEST_KEY");
    assertEquals(expectedKey, actualKey);
  }

  @Test(expected = NotFourOctetsException.class)
  public void testIpToInt32NotFourOctets() {
    Utils.ipToInt(":19");
  }

  @Test(expected = EmptyIpException.class)
  public void testIpToInt32EmptyIpException() {
    Utils.ipToInt("");
  }

  @Test
  public void testIpToInt32_localhost() {
    int expectedIp = (127 << 24) | 1;
    int actualIp = Utils.ipToInt("127.0.0.1");
    assertEquals(expectedIp, actualIp);
  }

  @Test
  public void testIpToInt32_above127() {
    int expectedIp = 176750850;
    int actualIp = Utils.ipToInt("10.137.1.2");
    assertEquals(expectedIp, actualIp);
  }

  @Test
  public void testIpToInt32_zeros() {
    int expectedIp = 0;
    int actualIp = Utils.ipToInt("0.0.0.0");
    assertEquals(expectedIp, actualIp);
  }

  @Test
  public void testIpToInt32_broadcast() {
    int expectedIp = -1;
    int actualIp = Utils.ipToInt("255.255.255.255");
    assertEquals(expectedIp, actualIp);
  }
}
