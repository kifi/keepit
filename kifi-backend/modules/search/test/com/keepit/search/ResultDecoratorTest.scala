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
import com.keepit.search.index.SymbolDecompounder

class ResultDecoratorTest extends Specification {

  val text =
    "For instance, on the planet Earth, man had always assumed that he was more intelligent than dolphins because he had achieved so much " +
    "- the wheel, New York, wars and so on - whilst all the dolphins had ever done was muck about in the water having a good time. " +
    "But conversely, the dolphins had always believed that they were far more intelligent than man - for precisely the same reasons."

  val analyzer = DefaultAnalyzer.forIndexingWithStemmer(Lang("en"))

  "ResultDecorator" should {
    "highlight terms" in {
      val highlights = ResultDecorator.highlight(text, analyzer, "f", Set("instance", "assume", "earth", "sky"))

      highlights.size === 3
      highlights.map{ case (start, end) => text.substring(start, end) }.toSet === Set("instance", "assumed", "Earth")
    }

    "highlight terms with multiple occurrences" in {
      val highlights = ResultDecorator.highlight(text, analyzer, "f", Set("dolphin"))

      highlights.size === 3
      highlights.map{ case (start, end) => text.substring(start, end) }.toSet === Set("dolphins")
    }

    "highlight overlapping terms" in {
      val text = "holidays.doc"

      var highlights = ResultDecorator.highlight(text, analyzer, "f", Set("holidays"))

      highlights.size === 1
      highlights.map{ case (start, end) => text.substring(start, end) }.toSet === Set("holidays.doc")

      highlights = ResultDecorator.highlight(text, analyzer, "f", Set("doc"))

      highlights.size === 1
      highlights.map{ case (start, end) => text.substring(start, end) }.toSet === Set("holidays.doc")

      highlights = ResultDecorator.highlight(text, analyzer, "f", Set("holidays", "doc"))

      highlights.size === 1
      highlights.map{ case (start, end) => text.substring(start, end) }.toSet === Set("holidays.doc")
    }
  }
}

