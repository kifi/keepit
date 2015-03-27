package com.keepit.rover.document.tika

import org.apache.tika.sax.WriteOutContentHandler
import org.specs2.mutable._

class KeywordValidatorContentHandlerTest extends Specification {
  "KeywordValidatorContentHandler" should {
    "pass all text to next handler" in {
      val out = new WriteOutContentHandler()
      val handler = new KeywordValidatorContentHandler(new KeywordValidator(Seq("aaa", "aaa bbb", "bbb ccc")), out)
      val txt = "let's see what is the output"
      val arr = txt.toCharArray()

      handler.startDocument()
      handler.characters(arr, 0, arr.length)
      handler.endDocument()

      out.toString === txt
    }
  }
}