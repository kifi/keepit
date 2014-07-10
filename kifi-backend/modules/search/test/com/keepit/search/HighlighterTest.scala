package com.keepit.search

import com.keepit.search.index.DefaultAnalyzer
import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import java.io.StringReader
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import com.keepit.search.result.Highlighter

class HighlighterTest extends Specification {

  val quote =
    "For instance, on the planet Earth, man had always assumed that he was more intelligent than dolphins because he had achieved so much " +
      "- the wheel, New York, wars and so on - whilst all the dolphins had ever done was muck about in the water having a good time. " +
      "But conversely, the dolphins had always believed that they were far more intelligent than man - for precisely the same reasons."

  val analyzer = DefaultAnalyzer.getAnalyzerWithStemmer(Lang("en"))

  "Highlighter" should {
    "highlight terms" in {
      val text = quote
      val highlights = Highlighter.highlight(text, analyzer, "f", Set("instance", "assume", "earth", "sky"))

      highlights.size === 3
      highlights.map { case (start, end) => text.substring(start, end) }.toSet === Set("instance", "assumed", "Earth")
    }

    "highlight terms with multiple occurrences" in {
      val text = quote
      val highlights = Highlighter.highlight(text, analyzer, "f", Set("dolphin"))

      highlights.size === 3
      highlights.map { case (start, end) => text.substring(start, end) }.toSet === Set("dolphins")
    }

    "highlight terms in url" in {
      val url = "http://www.scala-lang.org/api/current_version/index.html#package"

      var highlights = Highlighter.highlight(url, analyzer, "f", Set("scala"))

      highlights.size === 1
      highlights.map { case (start, end) => url.substring(start, end) }.toSet === Set("scala")

      highlights = Highlighter.highlight(url, analyzer, "f", Set("api"))

      highlights.size === 1
      highlights.map { case (start, end) => url.substring(start, end) }.toSet === Set("api")

      highlights = Highlighter.highlight(url, analyzer, "f", Set("index"))

      highlights.size === 1
      highlights.map { case (start, end) => url.substring(start, end) }.toSet === Set("index")

      highlights = Highlighter.highlight(url, analyzer, "f", Set("current"))

      highlights.size === 1
      highlights.map { case (start, end) => url.substring(start, end) }.toSet === Set("current")
    }

    "return an empty Seq if no match" in {
      val text = "Looking up into the night sky is looking into infinity"

      Highlighter.highlight(text, analyzer, "f", Set("scala")) must beEmpty

      val url = "http://kifi.com"

      Highlighter.highlight(text, analyzer, "f", Set("42go")) must beEmpty
    }
  }
}

