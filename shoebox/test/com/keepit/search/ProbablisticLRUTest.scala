package com.keepit.search

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import scala.math._
import java.util.Random
import java.nio.ByteBuffer
import java.io.File

@RunWith(classOf[JUnitRunner])
class ProbablisticLRUTest extends SpecificationWithJUnit {
  val rand = new Random
  rand.setSeed(123456789L)

  def create(tableSize: Int, numHashFuncs: Int, syncEvery: Int = 1000) = {
    ProbablisticLRU(tableSize, numHashFuncs, syncEvery)
  }

  "ProbabalisticLRUTest" should {
    "get the ranks of values" in {
      val lru = create(1000, 8)
      val key = rand.nextLong()
      val v1 = rand.nextLong()
      val v2 = rand.nextLong()
      val v3 = rand.nextLong()
      val v4 = rand.nextLong()
      val values = Seq(v1, v2, v3, v4)

      lru.put(key, v1)
      var ret = lru.get(key, values)

      ret.get(v1) must beSome[Int]
      ret(v1) === 4

      lru.put(key, v2)
      ret = lru.get(key, values)

      ret(v1) must be_<= (4)
      ret(v2) === 4

      lru.put(key, v3)
      lru.put(key, v4)
      ret = lru.get(key, values)

      ret(v4) === 4

      while (ret.getOrElse(v1, 0) >= 4) {
        lru.put(key, v4)
        ret = lru.get(key, values)
      }

      lru.put(key, v1)
      ret = lru.get(key, values)

      ret(v1) must be_>= (4)
    }

    "put/get multiple keys" in {
      val numPairs = 120
      val lru = create(1000, 10)

      val keys = for (i <- 0 until numPairs) yield rand.nextLong
      val values = for (i <- 0 until numPairs) yield rand.nextLong

      keys.zip(values).foreach{ case (k, v) => lru.put(k, v) }
      keys.zip(values).flatMap{ case (k, v) =>
        val ret = lru.get(k, values)
        if (!ret.isEmpty && ret.maxBy(_._2)._1 == v) None else Some(k)
      } must beEmpty
    }

    "sync every syncEvery" in {
      val syncEvery = 10
      val totalInserts = 100
      val lru = create(100, 8, syncEvery)
      lru.numInserts === 0
      lru.numSyncs === 0

      for (i <- 0 until totalInserts) {
        lru.numInserts === i.toLong
        lru.numSyncs === (i / syncEvery).toLong
        lru.put(1L, 1L)
      }

      lru.numInserts === totalInserts
      lru.numSyncs === totalInserts / syncEvery
    }
  }
}
