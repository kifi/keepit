package com.keepit.search.util

import java.util.Arrays
import java.util.Random
import org.specs2.mutable.Specification
import scala.collection.mutable.ArrayBuffer

class LongArrayBuilderTest extends Specification {

  "LongArrayBuilder" should {
    "build Long arrays correctly" in {
      var builder = new LongArrayBuilder
      val buf = new ArrayBuffer[Long]
      for (i <- 0 until 100) {
        builder += i.toLong
        buf += i.toLong

        Arrays.equals(builder.toArray, buf.toArray) === true
      }

      val rnd = new Random()
      val expected = (0 until 40000).map(_ => rnd.nextLong()).toArray
      builder = new LongArrayBuilder
      expected.foreach { i => builder += i }
      Arrays.equals(builder.toArray, expected) === true
    }
  }
}
