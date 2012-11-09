package com.keepit.search.line

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import scala.math._
import org.apache.lucene.analysis.WhitespaceAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import scala.collection.mutable.ArrayBuffer

@RunWith(classOf[JUnitRunner])
class LineTokenStreamTest extends SpecificationWithJUnit {

  "LineTokenStream" should {
    "tokenize strings aligning the position according to the line number" in {
      val ts = new LineTokenStream("B", Seq((0, "a b c"), (1, "d"), (2, "e f")), new WhitespaceAnalyzer)
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
      val ts = new LineTokenStream("B", Seq((0, longText)), new WhitespaceAnalyzer)
      val posIncrAttr = ts.getAttribute(classOf[PositionIncrementAttribute])
      var curPos = 0
      var count = 0
      while (ts.incrementToken) {
        count += 1
        curPos += posIncrAttr.getPositionIncrement
      }
      count === 3000
      curPos === LineQuery.MAX_POSITION_PER_LINE - LineQuery.LINE_GAP - 1
    }
    
    "maintain gaps between lines" in {
      val longText = (1 to 5000).map(_.toString).mkString(" ")
      val ts = new LineTokenStream("B", Seq((0, longText), (1, longText), (2, longText)), new WhitespaceAnalyzer)
      val posIncrAttr = ts.getAttribute(classOf[PositionIncrementAttribute])
      var curPos = 0
      var count = 0
      while (ts.incrementToken) {
        count += 1
        curPos += posIncrAttr.getPositionIncrement
        ((curPos % LineQuery.MAX_POSITION_PER_LINE) < (LineQuery.MAX_POSITION_PER_LINE - LineQuery.LINE_GAP)) === true
      }
      count === 5000 * 3
      curPos === LineQuery.MAX_POSITION_PER_LINE * 3 - LineQuery.LINE_GAP - 1
    }
  }
}