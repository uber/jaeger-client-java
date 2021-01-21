[![Build Status][ci-img]][ci] [![Coverage Status][cov-img]][cov] [![Released Version][maven-img]][maven] [![javadoc](https://javadoc.io/badge2/io.jaegertracing/jaeger-core/javadoc.svg)](https://javadoc.io/doc/io.jaegertracing/jaeger-core)
 [![FOSSA Status][fossa-img]][fossa]

# Jaeger's Tracing Instrumentation Library for Java

 * Intended to be used with [Jaeger](https://github.com/jaegertracing/jaeger) backend, but can also be configured to send traces to Zipkin.
 * Implements [OpenTracing Java API](https://github.com/opentracing/opentracing-java).
 * Supports Java 1.6 and above

#### Package rename to `io.jaegertracing`

The group ID `com.uber.jaeger` has been deprecated and moved to a different [repository][legacy-client-java].
Please switch to `io.jaegertracing`, as the old group ID will be maintained only for bug fixes.

## Contributing and Developing

Please see [CONTRIBUTING.md](CONTRIBUTING.md).

## Core Modules

Click through for more detailed docs on specific modules.

 * [jaeger-client](./jaeger-client): the module that instrumented applications should usually include
 * [jaeger-core](./jaeger-core): the core implementation of the OpenTracing API [![Java Docs][javadoc-badge]][javadoc]
 * [jaeger-thrift](./jaeger-thrift): set of components that send data to the backend
 
## Add-on Modules

 * [jaeger-zipkin](./jaeger-zipkin): compatibility layer for using the Jaeger Tracer to Zipkin-compatible backends
 * [jaeger-micrometer](./jaeger-micrometer): a metrics provider, to report internal Jaeger Client metrics to third-party backends, such as Prometheus
 * [jaeger-tracerresolver](./jaeger-tracerresolver): an [OpenTracing `TracingResolver`][tracerresolver] for the Jaeger Tracer.

## Importing Dependencies
All artifacts are published to Maven Central. Snapshot artifacts are also published to [Sonatype][sonatype].
Follow these [instructions][sonatype-snapshot-instructions] to add the snapshot repository to your build system.

**Please use the latest version:** [![Released Version][maven-img]][maven]

In the usual case, you just need to include the following dependency to your project:
```xml
<dependency>
    <groupId>io.jaegertracing</groupId>
    <artifactId>jaeger-client</artifactId>
    <version>$jaegerVersion</version>
</dependency>
```

This will bring a concrete sender, such as `jaeger-thrift`, as well as the `jaeger-tracerresolver` and `jaeger-core`.

### Thrift version conflicts
The Jaeger Java Client uses `org.apache.thrift:libthrift:0.11.0`. By default, declaring a dependency on the
`jaeger-thrift` module will bring a non-shaded version of Thrift (and others). A shaded version of the dependency is
available with the classifier `shadow`.

## Instantiating the Tracer

Please see [jaeger-core/README](./jaeger-core/README.md).

## Testing

When testing tracing instrumentation it is often useful to make sure
that all spans are being captured, which is not the case in production
configurations where heavy sampling is applied by default.
The following configuration can be provided to affect which sampling
is applied to the new traces:

```yaml
sampler:
   type: const # can either be const, probabilistic, or ratelimiting
   param: 1  # can either be an integer, a double, or an integer
```

The valid values for `type` are: 
 * `const`: configures a sampler that always makes the same decision
    for new traces depending on the `param`: always no for `param=0`,
    always yes otherwise.
 * `probabilistic`: configures a sampler that samples traces with
    probability equal to `param` (must be between `0.0` and `1.0`)
 * `ratelimiting`: configures a samlper that samples traces with a
    certain rate per second equal to `param`

### Debug Traces (Forced Sampling)

#### Programmatically

The OpenTracing API defines a `sampling.priority` standard tag that
can be used to affect the sampling of a span and its children:

```java
import io.opentracing.tag.Tags;

Tags.SAMPLING_PRIORITY.set(span, 1);
```

#### Via HTTP Headers

Jaeger Tracer also understands a special HTTP Header `jaeger-debug-id`,
which can be set in the incoming request, e.g.

```sh
curl -H "jaeger-debug-id: some-correlation-id" http://myhost.com
```

When Jaeger sees this header in the request that otherwise has no
tracing context, it ensures that the new trace started for this
request will be sampled in the "debug" mode (meaning it should survive
all downsampling that might happen in the collection pipeline), and
the root span will have a tag as if this statement was executed:

```java
span.setTag("jaeger-debug-id", "some-correlation-id")
```

This allows using Jaeger UI to find the trace by this tag.

## License
  
[Apache 2.0 License](./LICENSE).

[ci-img]: https://github.com/jaegertracing/jaeger-client-java/workflows/Unit%20Tests/badge.svg?branch=master
[ci]: https://github.com/jaegertracing/jaeger-client-java/actions?query=branch%3Amaster
[cov-img]: https://codecov.io/gh/jaegertracing/jaeger-client-java/branch/master/graph/badge.svg
[cov]: https://codecov.io/github/jaegertracing/jaeger-client-java/
[maven-img]: https://img.shields.io/maven-central/v/io.jaegertracing/jaeger-core.svg?maxAge=2000
[maven]: http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22io.jaegertracing%22
[fossa-img]: https://app.fossa.io/api/projects/git%2Bgithub.com%2Fjaegertracing%2Fjaeger-client-java.svg?type=shield
[fossa]: https://app.fossa.io/projects/git%2Bgithub.com%2Fjaegertracing%2Fjaeger-client-java?ref=badge_shield
[sonatype]: https://oss.sonatype.org/content/repositories/snapshots/io/jaegertracing/
[sonatype-snapshot-instructions]: http://stackoverflow.com/questions/7715321/how-to-download-snapshot-version-from-maven-snapshot-repository
[tracerresolver]: https://github.com/opentracing-contrib/java-tracerresolver
[legacy-client-java]: https://github.com/jaegertracing/legacy-client-java
[javadoc]: http://javadoc.io/doc/io.jaegertracing/jaeger-core
[javadoc-badge]: http://javadoc.io/badge/io.jaegertracing/jaeger-core.svg
