package com.keepit.search.tracking

import org.specs2.mutable._
import scala.util.Random

class MultiHashFilterBuilderTest extends Specification {

  class TestEntity
  case class TestFilterBuilder(tableSize: Int, numHashFuncs: Int, minHits: Int) extends MultiHashFilterBuilder[TestEntity]

  val rand = new Random

  "MultiHashFilterBuilder" should {

    "do a basic serialization flow" in {
      Seq(0, 1, 123, 1234).forall { filterSize =>
        val bytes = (0 until filterSize).map { i => rand.nextInt.toByte }.toArray
        val filter = new MultiHashFilter[TestEntity](filterSize, bytes, 2, 1)

        val builder = TestFilterBuilder(filterSize, 2, 1)
        val encoded = builder.writes(Some(filter))

        val decoded = builder.reads(encoded)
        decoded.isDefined === true
        decoded.get.getFilter === bytes
      } === true
    }

    "serialize data starting with zero" in {
      val filterSize = 5
      val bytes = (0 until filterSize).map(_.toByte).toArray
      val filter = new MultiHashFilter[TestEntity](filterSize, bytes, 2, 1)

      val builder = TestFilterBuilder(filterSize, 2, 1)
      val encoded = builder.writes(Some(filter))

      val decoded = builder.reads(encoded)
      decoded.isDefined === true
      decoded.get.getFilter === bytes
    }

    "serialize None" in {
      val builder = TestFilterBuilder(10, 2, 1)
      val encoded = builder.writes(None)
      val decoded = builder.reads(encoded)
      decoded === None
    }
  }
}
