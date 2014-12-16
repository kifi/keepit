package com.keepit.search.tracking

import java.util.Random

import org.specs2.mutable._

class MultiHashFilterTest extends Specification {
  val rand = new Random(123456789L)

  def create(tableSize: Int, numHashFuncs: Int, minHits: Int, syncEvery: Int = 1000) = {
    MultiHashFilter(tableSize, numHashFuncs, minHits)
  }

  "MultiHashFilter" should {
    "put/test keys" in {
      val hf = create(3000, 3, 1)
      val keys = (0 until 10000).map(i => rand.nextLong).toList

      keys.foreach(k => hf.put(k))
      keys.take(100).exists(k => !hf.mayContain(k)) === true
      keys.drop(keys.size - 100).forall(k => hf.mayContain(k)) === true

      keys.take(100).foreach(k => hf.put(k))
      keys.take(100).forall(k => hf.mayContain(k)) === true
    }
  }
}
