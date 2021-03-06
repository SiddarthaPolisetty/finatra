package com.twitter.finatra.kafkastreams.transformer

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver, StatsReceiver}
import com.twitter.finatra.kafka.test.utils.InMemoryStatsUtil
import com.twitter.finatra.kafkastreams.config.KafkaStreamsConfig
import com.twitter.finatra.kafkastreams.transformer.domain.Time
import com.twitter.finatra.kafkastreams.transformer.stores.CachingKeyValueStores
import com.twitter.finatra.kafkastreams.transformer.watermarks.Watermark
import com.twitter.inject.Test
import com.twitter.util.Duration
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.processor._
import org.apache.kafka.streams.processor.internals.{ProcessorNode, RecordCollector, ToInternal}
import org.apache.kafka.streams.state.internals.FinatraStores
import org.apache.kafka.test.{InternalMockProcessorContext, MockProcessorNode, NoOpRecordCollector, TestUtils}
import org.hamcrest.{BaseMatcher, Description}
import org.mockito.{Matchers, Mockito}

class FinatraTransformerTest extends Test with com.twitter.inject.Mockito {
  val firstMessageTimestamp = 100000
  val firstKey = "key1"
  val firstValue = "value1"

  val secondMessageTimestamp = 200000
  val secondKey = "key2"
  val secondValue = "value2"

  test("watermark processing when forwarding from onMessage") {
    val transformer =
      new FinatraTransformer[String, String, String, String](NullStatsReceiver) {
        override def onMessage(messageTime: Time, key: String, value: String): Unit = {
          forward(key, value, watermark.timeMillis)
        }
      }

    val context = smartMock[ProcessorContext]
    context.taskId() returns new TaskId(0, 0)
    context.timestamp returns firstMessageTimestamp

    transformer.init(context)
    transformer.transform(firstKey, firstValue)
    transformer.watermark should be(Watermark(firstMessageTimestamp - 1))
    assertForwardedMessage(context, firstKey, firstValue, firstMessageTimestamp)

    context.timestamp returns secondMessageTimestamp
    transformer.transform(secondKey, secondValue)
    transformer.watermark should be(Watermark(firstMessageTimestamp - 1))
    assertForwardedMessage(context, secondKey, secondValue, firstMessageTimestamp)

    transformer.onFlush()
    transformer.watermark should be(Watermark(secondMessageTimestamp - 1))
  }

  test("watermark processing when forwarding from caching flush listener") {
    val transformer =
      new FinatraTransformer[String, String, String, String](NullStatsReceiver)
      with CachingKeyValueStores[String, String, String, String] {
        private val cache =
          getCachingKeyValueStore[String, String]("mystore", onFlushedEntry(_, _, _))

        override def commitInterval: Duration = 1.second

        override def onMessage(messageTime: Time, key: String, value: String): Unit = {
          cache.put(key, value)
        }

        private def onFlushedEntry(store: String, key: String, value: String): Unit = {
          forward(key = key, value = value, timestamp = watermark.timeMillis)
        }
      }

    val inMemoryStatsReceiver = new InMemoryStatsReceiver
    val statUtils = new InMemoryStatsUtil(inMemoryStatsReceiver)

    val context = Mockito.spy(new CachingFinatraMockProcessorContext(inMemoryStatsReceiver))
    transformer.init(context)

    context.setTime(firstMessageTimestamp)
    transformer.transform(firstKey, firstValue)

    context.setTime(secondMessageTimestamp)
    transformer.transform(secondKey, secondValue)
    statUtils.assertGauge("stores/mystore/numCacheEntries", 2)

    transformer.onFlush()
    assertForwardedMessage(context, firstKey, firstValue, secondMessageTimestamp)
    assertForwardedMessage(context, secondKey, secondValue, secondMessageTimestamp)
    statUtils.assertGauge("stores/mystore/numCacheEntries", 0)
  }

  private def assertForwardedMessage(
    context: ProcessorContext,
    firstKey: String,
    firstValue: String,
    firstMessageTimestamp: Int
  ): Unit = {
    org.mockito.Mockito
      .verify(context)
      .forward(meq(firstKey), meq(firstValue), matchTo(firstMessageTimestamp - 1))
  }

  private def matchTo(expectedTimestamp: Int): To = {
    Matchers.argThat(new BaseMatcher[To] {
      override def matches(to: scala.Any): Boolean = {
        val toInternal = new ToInternal
        toInternal.update(to.asInstanceOf[To])
        toInternal.timestamp() == expectedTimestamp
      }

      override def describeTo(description: Description): Unit = {
        description.appendText(s"To(timestamp = $expectedTimestamp)")
      }
    })
  }

  val config = new KafkaStreamsConfig()
    .commitInterval(Duration.Top)
    .applicationId("test-app")
    .bootstrapServers("127.0.0.1:1000")

  class CachingFinatraMockProcessorContext(statsReceiver: StatsReceiver)
      extends InternalMockProcessorContext(
        TestUtils.tempDirectory,
        new StreamsConfig(config.properties)) {

    override def currentNode(): ProcessorNode[_, _] = {
      new MockProcessorNode()
    }

    override def schedule(
      interval: Long,
      `type`: PunctuationType,
      callback: Punctuator
    ): Cancellable = {
      new Cancellable {
        override def cancel(): Unit = {
          //no-op
        }
      }
    }
    override def getStateStore(name: String): StateStore = {
      val storeBuilder = FinatraStores
        .keyValueStoreBuilder(
          statsReceiver,
          FinatraStores.persistentKeyValueStore(name),
          Serdes.String(),
          Serdes.String()
        )
        .withCachingEnabled()

      val store = storeBuilder.build
      store.init(this, store)
      store
    }

    override def recordCollector(): RecordCollector = {
      new NoOpRecordCollector
    }

    override def forward[K, V](key: K, value: V, to: To): Unit = {}
  }

}
