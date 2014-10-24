package com.keepit.search.util

import java.util.Arrays
import java.util.Random
import org.specs2.mutable.Specification
import scala.collection.mutable.ArrayBuffer

class IntArrayBuilderTest extends Specification {

  "IntArrayBuilder" should {
    "build int arrays correctly" in {
      var builder = new IntArrayBuilder
      val buf = new ArrayBuffer[Int]
      for (i <- 0 until 100) {
        builder += i
        buf += i

        Arrays.equals(builder.toArray, buf.toArray) === true
      }

      val rnd = new Random()
      val expected = (0 until 40000).map(_ => rnd.nextInt()).toArray
      builder = new IntArrayBuilder
      expected.foreach { i => builder += i }
      Arrays.equals(builder.toArray, expected) === true
    }
  }
}
