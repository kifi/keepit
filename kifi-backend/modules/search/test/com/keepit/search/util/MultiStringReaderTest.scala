package com.keepit.search.util

import org.specs2.mutable._

class MultiStringReaderTest extends Specification {
  val chars = Array("a", "b", "c", "", "d", "e", "f", "h", "i", "j", "k")
  var strings = (0 until 10).map(i => chars(i % chars.length) * 10).toArray

  "MultiStringReader" should {
    "read all strings by read()" in {
      val expected = strings.mkString("")
      val reader = new MultiStringReader(strings)
      val result = new StringBuilder()

      var c = reader.read()
      while (c >= 0) {
        result.append(c.toChar)
        c = reader.read()
      }
      (result.toString == expected) === true
    }
    "read all strings by read(buf)" in {
      val expected = strings.mkString("")
      (1 to 110).forall { bufSize =>
        val reader = new MultiStringReader(strings)
        val result = new StringBuilder()
        val readBuf = new Array[Char](bufSize)

        var amount = reader.read(readBuf)
        while (amount > 0) {
          result.appendAll(readBuf, 0, amount)
          amount = reader.read(readBuf)
        }
        result.toString === expected
      } === true

    }
    "read all strings by read(buf, off, len)" in {
      val expected = strings.mkString("")
      (1 to 110).forall { bufSize =>
        val reader = new MultiStringReader(strings)
        val result = new StringBuilder()
        val readBuf = new Array[Char](bufSize + 3)

        var amount = reader.read(readBuf, 3, bufSize)
        while (amount > 0) {
          result.appendAll(readBuf, 3, amount)
          amount = reader.read(readBuf, 3, bufSize)
        }
        result.toString === expected
      } === true
    }
  }
}
