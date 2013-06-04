package com.keepit.search

import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import scala.math._
import java.util.Random
import java.nio.ByteBuffer
import java.io.File

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
      keys.take(100).forall(k => !hf.mayContain(k)) == true
      keys.drop(keys.size - 100).forall(k => hf.mayContain(k)) == true

      keys.take(100).foreach(k => hf.put(k))
      keys.take(100).forall(k => hf.mayContain(k)) == true
    }
  }
}
