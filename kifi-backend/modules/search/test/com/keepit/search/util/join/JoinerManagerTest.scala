package com.keepit.search.util.join

import org.specs2.mutable._
import scala.util.Random
import scala.collection.mutable

class JoinerManagerTest extends Specification {

  class TstJoiner(result: mutable.HashSet[Long]) extends Joiner {
    def join(reader: DataBufferReader): Unit = {}
    def flush(): Unit = { result += id }
    def clear(): Unit = {}
  }

  class TstJoinerManager(initialCapacity: Int) extends JoinerManager(initialCapacity) {
    val result = new mutable.HashSet[Long]
    var numCreated = 0
    def create(): Joiner = {
      numCreated += 1
      new TstJoiner(result)
    }
  }

  private def loadAndFlush(mgr: TstJoinerManager, numGets: Int, maxId: Int = Int.MaxValue): mutable.HashSet[Long] = {
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

  "JoinerManager" should {

    "manage mapping from ids to joiners (small initial capacity, sparse ids)" in {
      val mgr = new TstJoinerManager(0)
      val ids = loadAndFlush(mgr, 100)
      mgr.numCreated == ids.size
      mgr.result === ids
    }

    "manage mapping from ids to joiners (large initial capacity, sparse ids)" in {
      val mgr = new TstJoinerManager(1000)
      val ids = loadAndFlush(mgr, 100)
      mgr.numCreated == ids.size
      mgr.result === ids
    }

    "manage mapping from ids to joiners (small initial capacity, dense ids)" in {
      val mgr = new TstJoinerManager(0)
      val ids = loadAndFlush(mgr, 100, 50)
      mgr.numCreated == ids.size
      mgr.result === ids
    }

    "manage mapping from ids to joiners (large initial capacity, dense ids)" in {
      val mgr = new TstJoinerManager(1000)
      val ids = loadAndFlush(mgr, 100, 50)
      mgr.numCreated == ids.size
      mgr.result === ids
    }

    "reuse joiners" in {
      val ids = new mutable.HashSet[Long]
      val mgr = new TstJoinerManager(0)

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
