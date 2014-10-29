package com.keepit.search.util.join

import org.specs2.mutable._
import scala.util.Random
import scala.collection.mutable

class AggregationContextManagerTest extends Specification {

  class TstAggregationContext(result: mutable.HashSet[Long]) extends AggregationContext {
    def join(reader: DataBufferReader): Unit = {}
    def flush(): Unit = { result += id }
    def clear(): Unit = {}
  }

  class TstAggregationContextManager(initialCapacity: Int) extends AggregationContextManager(initialCapacity) {
    val result = new mutable.HashSet[Long]
    var numCreated = 0
    def create(): AggregationContext = {
      numCreated += 1
      new TstAggregationContext(result)
    }
  }

  private def loadAndFlush(mgr: TstAggregationContextManager, numGets: Int, maxId: Int = Int.MaxValue): mutable.HashSet[Long] = {
    val ids = new mutable.HashSet[Long]
    for (i <- 0 until numGets) {
      val id = rand.nextInt(maxId).toLong
      ids += id
      mgr.get(id)
    }
    mgr.flush()
    ids
  }

  val rand = new Random

  "AggregationManager" should {

    "manage mapping from ids to aggregation contexts (small initial capacity, sparse ids)" in {
      val mgr = new TstAggregationContextManager(0)
      val ids = loadAndFlush(mgr, 100)
      mgr.numCreated == ids.size
      mgr.result === ids
    }

    "manage mapping from ids to aggregation contexts (large initial capacity, sparse ids)" in {
      val mgr = new TstAggregationContextManager(1000)
      val ids = loadAndFlush(mgr, 100)
      mgr.numCreated == ids.size
      mgr.result === ids
    }

    "manage mapping from ids to aggregation contexts (small initial capacity, dense ids)" in {
      val mgr = new TstAggregationContextManager(0)
      val ids = loadAndFlush(mgr, 100, 50)
      mgr.numCreated == ids.size
      mgr.result === ids
    }

    "manage mapping from ids to aggregation contexts (large initial capacity, dense ids)" in {
      val mgr = new TstAggregationContextManager(1000)
      val ids = loadAndFlush(mgr, 100, 50)
      mgr.numCreated == ids.size
      mgr.result === ids
    }

    "reuse aggregation contexts" in {
      val ids = new mutable.HashSet[Long]
      val mgr = new TstAggregationContextManager(0)

      for (j <- 0 until 10) {
        for (i <- 0 until 20) {
          val id = (i + j * 5).toLong
          ids += id
          mgr.get(id)
        }
        mgr.flush()
      }

      mgr.result.size == (4 + 19 * 5)
      mgr.result === ids
      mgr.numCreated == 20
    }
  }
}
