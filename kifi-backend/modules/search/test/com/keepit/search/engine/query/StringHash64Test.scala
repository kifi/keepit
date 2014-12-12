package com.keepit.search.engine.query

import org.specs2.mutable.Specification

class StringHash64Test extends Specification {

  "StringHash64" should {
    "hash strings" in {
      var h = new StringHash64(123456789L)
      h.update("this is a test string")

      h.get === 698300024818497877L

      h = new StringHash64(123456789L)
      h.update("this is another test string")

      h.get === 3797257411387707953L
    }

    "hash strings in pieces" in {
      var h = new StringHash64(123456789L)
      h.update("this is ")
      h.update("a test string")

      h.get === 698300024818497877L
    }

    "hash strings to different values for different seeds" in {
      var h = new StringHash64(123456789L)
      h.update("this is the same string")

      h.get === -6219435880013801415L

      h = new StringHash64(987654321L)
      h.update("this is the same string")

      h.get === 866074918117046197L
    }

    "hash a string as a sequence of Char" in {
      var h = new StringHash64(0L)
      h.update("a string as a sequence of Char")

      h.get === -591087695398117057L

      h = new StringHash64(0L)
      "a string as a sequence of Char".foreach { c => h.update(c) }

      h.get === -591087695398117057L
    }
  }
}
