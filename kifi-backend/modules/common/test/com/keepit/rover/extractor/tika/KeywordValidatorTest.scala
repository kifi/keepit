package com.keepit.rover.extractor.tika

import org.specs2.mutable._

class KeywordValidatorTest extends Specification {
  "KeywordValidator" should {
    "find keywords in the content" in {
      val validator = new KeywordValidator(Seq("aaa", "aaa bbb", "bbb ccc"))
      val txt = "bbb aaa aaa ccc bbb ccc"
      val arr = txt.toCharArray()

      validator.startDocument()
      validator.characters(arr, 0, arr.length)
      validator.endDocument()

      validator.keywords === Seq("aaa", "bbb ccc")
    }

    "collpases multiple spaces in input to a single space" in {
      val validator = new KeywordValidator(Seq("aaa bbb", "bbb ccc", "ccc ddd"))
      val txt = "  aaa  bbb    ccc     ddd"
      val arr = txt.toCharArray()

      validator.startDocument()
      validator.characters(arr, 0, arr.length)
      validator.endDocument()

      validator.keywords === Seq("aaa bbb", "bbb ccc", "ccc ddd")
    }

    "translate a symbol to a space" in {
      val validator = new KeywordValidator(Seq("aaa bbb", "bbb ccc", "ccc ddd", "ddd eee"))
      val txt = "  aaa.bbb , ccc///ddd ! ! ! eee"
      val arr = txt.toCharArray()

      validator.startDocument()
      validator.characters(arr, 0, arr.length)
      validator.endDocument()

      validator.keywords === Seq("aaa bbb", "bbb ccc", "ccc ddd", "ddd eee")
    }

    "be case insensitive" in {
      val validator = new KeywordValidator(Seq("aaa bbb", "bbb ccc"))
      val txt = "AAA Bbb ccC"
      val arr = txt.toCharArray()
      validator.startDocument()
      validator.characters(arr, 0, arr.length)
      validator.endDocument()

      validator.keywords === Seq("aaa bbb", "bbb ccc")
    }

    "be ok with segmentation" in {
      val validator = new KeywordValidator(Seq("aaa bbb", "bbb ccc"))
      val txt = Array("aa", "a bbb ", " ccc")

      validator.startDocument()
      txt.foreach { txt =>
        val arr = txt.toCharArray()
        validator.characters(arr, 0, arr.length)
      }
      validator.endDocument()

      validator.keywords === Seq("aaa bbb", "bbb ccc")
    }
  }
}