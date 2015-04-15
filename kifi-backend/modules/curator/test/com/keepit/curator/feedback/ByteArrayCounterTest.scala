package com.keepit.curator.feedback

import org.specs2.mutable.Specification
import play.api.libs.json._

class ByteArrayCounterTest extends Specification {
  "ByteArrayCounter" should {
    "can set and retrieve value" in {
      val counter = ByteArrayCounter.empty(256)
      (0 until 256).foreach { i => counter.set(i, i) }
      (0 until 256).map { counter.get(_) }.toList === (0 until 256).toList
    }

    "increments correctly" in {
      val counter = ByteArrayCounter.empty(256)
      (0 until 10).foreach { i => counter.increment(i, i + 1) }
      (0 until 10).map { counter.get(_) } === (1 to 10).toList
      (0 until 256).map { counter.get(_) }.sum === (1 to 10).sum
    }

    "correctly tests if we can increment" in {
      val counter = ByteArrayCounter.empty(2)
      counter.set(0, 254)
      counter.canIncrement(0) === true
      counter.canIncrement(0, 2) === false
    }

    "serializes to json" in {
      val counter = ByteArrayCounter.empty(256)
      (0 until 256).foreach { i => counter.set(i, i) }
      val js = Json.toJson(counter)
      Json.fromJson[ByteArrayCounter](js).get.toArray().toList === (0 until 256).toList
    }
  }

}
