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

package com.uber.jaeger.reporters.protocols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;
import com.uber.jaeger.Tracer;
import com.uber.jaeger.reporters.InMemoryReporter;
import com.uber.jaeger.samplers.ConstSampler;
import com.uber.jaeger.thriftjava.Log;
import com.uber.jaeger.thriftjava.Tag;
import com.uber.jaeger.thriftjava.TagType;
import io.opentracing.Span;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DataProviderRunner.class)
public class JaegerThriftSpanConverterTest {
  Tracer tracer;

  @Before
  public void setUp() {
    tracer =
        new Tracer.Builder("test-service-name", new InMemoryReporter(), new ConstSampler(true))
            .build();
  }

  @DataProvider
  public static Object[][] dataProviderBuildTag() {
    return new Object[][] {
        { "value", TagType.STRING, "value" },
        { new Long(1), TagType.LONG, new Long(1) },
        { new Integer(1), TagType.LONG, new Long(1) },
        { new Short((short) 1), TagType.LONG, new Long(1) },
        { new Double(1), TagType.DOUBLE, new Double(1) },
        { new Float(1), TagType.DOUBLE, new Double(1) },
        { new Byte((byte) 1), TagType.STRING, "1" },
        { true, TagType.BOOL, true },
        { new ArrayList<String>() {
            {
              add("hello");
            }
          }, TagType.STRING, "[hello]" }
    };
  }

  @Test
  @UseDataProvider("dataProviderBuildTag")
  public void testBuildTag(Object tagValue, TagType tagType, Object expected) {
    Tag tag = JaegerThriftSpanConverter.buildTag("key", tagValue);
    assertEquals(tagType, tag.getVType());
    assertEquals("key", tag.getKey());
    switch (tagType) {
      case STRING:
      default:
        assertEquals(expected, tag.getVStr());
        break;
      case BOOL:
        assertEquals(expected, tag.isVBool());
        break;
      case LONG:
        assertEquals(expected, tag.getVLong());
        break;
      case DOUBLE:
        assertEquals(expected, tag.getVDouble());
        break;
      case BINARY:
        break;
    }
  }

  @Test
  public void testBuildTags() {
    Map<String, Object> tags = new HashMap<String, Object>();
    tags.put("key", "value");

    List<Tag> thriftTags = JaegerThriftSpanConverter.buildTags(tags);
    assertNotNull(thriftTags);
    assertEquals(1, thriftTags.size());
    assertEquals("key", thriftTags.get(0).getKey());
    assertEquals("value", thriftTags.get(0).getVStr());
    assertEquals(TagType.STRING, thriftTags.get(0).getVType());
  }

  @Test
  public void testConvertSpan() {
    Map<String, Object> fields = new HashMap<String, Object>();
    fields.put("k", "v");

    Span span = tracer.buildSpan("operation-name").start();
    span = span.log(1, "key", "value");
    span = span.log(1, fields);

    com.uber.jaeger.thriftjava.Span thriftSpan = JaegerThriftSpanConverter.convertSpan((com.uber.jaeger.Span) span);

    assertEquals("operation-name", thriftSpan.getOperationName());
    assertEquals(2, thriftSpan.getLogs().size());
    Log thriftLog = thriftSpan.getLogs().get(0);
    assertEquals(1, thriftLog.getTimestamp());
    assertEquals(2, thriftLog.getFields().size());
    Tag thriftTag = thriftLog.getFields().get(0);
    assertEquals("event", thriftTag.getKey());
    assertEquals("key", thriftTag.getVStr());
    thriftTag = thriftLog.getFields().get(1);
    assertEquals("payload", thriftTag.getKey());
    assertEquals("value", thriftTag.getVStr());

    thriftLog = thriftSpan.getLogs().get(1);
    assertEquals(1, thriftLog.getTimestamp());
    assertEquals(1, thriftLog.getFields().size());
    thriftTag = thriftLog.getFields().get(0);
    assertEquals("k", thriftTag.getKey());
    assertEquals("v", thriftTag.getVStr());
  }

  @Test
  public void testTruncateString() {
    String testString =
        "k9bHT50f9JNpPUggw3Qz\n"
        + "Q1MUhMobIMPA5ItaB3KD\n"
        + "vNUoBPRjOpJw2C46vgn3\n"
        + "UisXI5KIIH8Wd8uqJ8Wn\n"
        + "Z8NVmrcpIBwxc2Qje5d6\n"
        + "1mJdQnPMc3VmX1v75As8\n"
        + "pUyoicWVPeGEidRuhHpt\n"
        + "R1sIR1YNjwtBIy9Swwdq\n"
        + "LUIZXdLcPmCvQVPB3cYw\n"
        + "VGAtFXG7D8ksLsKw94eY\n"
        + "c7PNm74nEV3jIIvlJ217\n"
        + "SLBfUBHW6SEjrHcz553i\n"
        + "VSjpBvJYXR6CsoEMGce0\n"
        + "LqSypCXJHDAzb0DL1w8B\n"
        + "kS9g0wCgregSAlq63OIf";
    assertEquals(256, JaegerThriftSpanConverter.truncateString(testString).length());
  }
}
