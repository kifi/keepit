package com.keepit.scraper.extractor

import org.specs2.mutable._
import org.apache.tika.sax.WriteOutContentHandler

class KeywordValidatorContentHandlerTest extends Specification {
  "KeywordValidatorContentHandler" should {
    "pass all text to next handler" in {
       val out = new WriteOutContentHandler()
       val handler = new KeywordValidatorContentHandler(Seq("aaa", "aaa bbb", "bbb ccc"), out)
       val txt = "let's see what is the output"
       val arr = txt.toCharArray()

       handler.startDocument()
       handler.characters(arr, 0, arr.length)
       handler.endDocument()

       out.toString === txt
    }

    "find keywords in the content" in {
       val out = new WriteOutContentHandler()
       val handler = new KeywordValidatorContentHandler(Seq("aaa", "aaa bbb", "bbb ccc"), out)
       val txt = "bbb aaa aaa ccc bbb ccc"
       val arr = txt.toCharArray()

       handler.startDocument()
       handler.characters(arr, 0, arr.length)
       handler.endDocument()

       handler.keywords === Seq("aaa", "bbb ccc")
    }

    "collpases multiple spaces in input to a single space" in {
       val out = new WriteOutContentHandler()
       val handler = new KeywordValidatorContentHandler(Seq("aaa bbb", "bbb ccc", "ccc ddd"), out)
       val txt = "  aaa  bbb    ccc     ddd"
       val arr = txt.toCharArray()

       handler.startDocument()
       handler.characters(arr, 0, arr.length)
       handler.endDocument()

       handler.keywords === Seq("aaa bbb", "bbb ccc", "ccc ddd")
    }

    "translate a symbol to a space" in {
       val out = new WriteOutContentHandler()
       val handler = new KeywordValidatorContentHandler(Seq("aaa bbb", "bbb ccc", "ccc ddd", "ddd eee"), out)
       val txt = "  aaa.bbb , ccc///ddd ! ! ! eee"
       val arr = txt.toCharArray()

       handler.startDocument()
       handler.characters(arr, 0, arr.length)
       handler.endDocument()

       handler.keywords === Seq("aaa bbb", "bbb ccc", "ccc ddd", "ddd eee")
    }

    "be case insensitive" in {
       val out = new WriteOutContentHandler()
       val handler = new KeywordValidatorContentHandler(Seq("aaa bbb", "bbb ccc"), out)
       val txt = "AAA Bbb ccC"
       val arr = txt.toCharArray()
       handler.startDocument()
       handler.characters(arr, 0, arr.length)
       handler.endDocument()

       handler.keywords === Seq("aaa bbb", "bbb ccc")
    }

    "be ok with segmentation" in {
       val out = new WriteOutContentHandler()
       val handler = new KeywordValidatorContentHandler(Seq("aaa bbb", "bbb ccc"), out)
       val txt = Array("aa", "a bbb ", " ccc")

       handler.startDocument()
       txt.foreach{ txt =>
         val arr = txt.toCharArray()
         handler.characters(arr, 0, arr.length)
       }
       handler.endDocument()

       handler.keywords === Seq("aaa bbb", "bbb ccc")
    }
  }
}