package com.keepit.search.line

import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import scala.math._
import com.keepit.search.index.DefaultAnalyzer
import org.apache.lucene.analysis.core.WhitespaceAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import scala.collection.mutable.ArrayBuffer
import org.apache.lucene.util.Version
import java.io.StringReader

class LineTokenStreamTest extends Specification {
  val analyzer = new WhitespaceAnalyzer(Version.LUCENE_41)
  "LineTokenStream" should {
    "tokenize strings aligning the position according to the line number" in {
      val ts = new LineTokenStream("B", Seq((0, "a b c"), (1, "d"), (2, "e f")), (f, t) => analyzer.tokenStream(f, new StringReader(t)))
      val expected = Array((1, "a"), (2, "b"), (3, "c"), (2049, "d"), (4097, "e"), (4098, "f"))
      val charAttr = ts.getAttribute(classOf[CharTermAttribute])
      val posIncrAttr = ts.getAttribute(classOf[PositionIncrementAttribute])
      var curPos = 0
      val buf = new ArrayBuffer[(Int, String)]
      while (ts.incrementToken) {
        curPos += posIncrAttr.getPositionIncrement
        buf += ((curPos, charAttr.toString))
      }
      buf.toArray === expected
    }

    "cap the position" in {
      val longText = (1 to 3000).map(_.toString).mkString(" ")
      val ts = new LineTokenStream("B", Seq((0, longText)), (f, t) => analyzer.tokenStream(f, new StringReader(t)))
      val posIncrAttr = ts.getAttribute(classOf[PositionIncrementAttribute])
      var curPos = 0
      var count = 0
      while (ts.incrementToken) {
        count += 1
        curPos += posIncrAttr.getPositionIncrement
      }
      count === 3000
      curPos === LineField.MAX_POSITION_PER_LINE - LineField.LINE_GAP - 1
    }

    "maintain gaps between lines" in {
      val longText = (1 to 5000).map(_.toString).mkString(" ")
      val ts = new LineTokenStream("B", Seq((0, longText), (1, longText), (2, longText)), (f, t) => analyzer.tokenStream(f, new StringReader(t)))
      val posIncrAttr = ts.getAttribute(classOf[PositionIncrementAttribute])
      var curPos = 0
      var count = 0
      while (ts.incrementToken) {
        count += 1
        curPos += posIncrAttr.getPositionIncrement
        ((curPos % LineField.MAX_POSITION_PER_LINE) < (LineField.MAX_POSITION_PER_LINE - LineField.LINE_GAP)) === true
      }
      count === 5000 * 3
      curPos === LineField.MAX_POSITION_PER_LINE * 3 - LineField.LINE_GAP - 1
    }
  }
}
