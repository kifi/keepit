package com.keepit.search.line
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.Field
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.document.FieldType
import org.apache.lucene.document.TextField

object LineField {
  val MAX_POSITION_PER_LINE = 2048
  val LINE_GAP = 3
}

trait LineFieldBuilder {
  def buildLineField(fieldName: String, lines: Seq[(Int, String)], tokenStreamFunc: (String, String)=>Option[TokenStream], fieldType: FieldType = TextField.TYPE_NOT_STORED) = {
    new Field(fieldName, new LineTokenStream(fieldName, lines, tokenStreamFunc), fieldType)
  }
}

class LineTokenStream(fieldName: String, lines: Seq[(Int, String)], tokenStreamFunc: (String, String)=>Option[TokenStream]) extends TokenStream {
  val termAttr = addAttribute(classOf[CharTermAttribute])
  val posIncrAttr = addAttribute(classOf[PositionIncrementAttribute])
  val lineIter = lines.sortBy(_._1).iterator

  val emptyTokenStream = new TokenStream {
    override def incrementToken() = false
  }
  var baseTokenStream = emptyTokenStream

  var gap = 0
  var curPos = 0
  var posLimit = 0
  var baseHasPosIncrAttr = false

  private def lineRange(lineNo: Int) = (lineNo * LineField.MAX_POSITION_PER_LINE,
                                        (lineNo + 1) * LineField.MAX_POSITION_PER_LINE - LineField.LINE_GAP)

  override def incrementToken(): Boolean = {
    clearAttributes()

    var incr = 0
    var moreToken = baseTokenStream.incrementToken()
    while (!moreToken && lineIter.hasNext) {
      val (lineNo, text) = lineIter.next
      val (lineStart, lineEnd) = lineRange(lineNo)
      incr = lineStart - curPos
      posLimit = lineEnd - 1
      baseTokenStream = tokenStreamFunc(fieldName, text).getOrElse(emptyTokenStream)
      baseHasPosIncrAttr = baseTokenStream.hasAttribute(classOf[PositionIncrementAttribute])
      baseTokenStream.reset
      moreToken = baseTokenStream.incrementToken()
    }
    moreToken match {
      case true =>
        termAttr.append(baseTokenStream.getAttribute(classOf[CharTermAttribute]))
        if (baseHasPosIncrAttr) {
          val basePosIncrAttr = baseTokenStream.getAttribute(classOf[PositionIncrementAttribute])
          incr += basePosIncrAttr.getPositionIncrement
        } else {
          incr += 1
        }
        if (curPos + incr > posLimit) incr = posLimit - curPos
        posIncrAttr.setPositionIncrement(incr)
        curPos += incr
        incr = 0
        true
      case false => false
    }
  }
}
