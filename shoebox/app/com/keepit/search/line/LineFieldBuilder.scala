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
  private[this] val termAttr = addAttribute(classOf[CharTermAttribute])
  private[this] val posIncrAttr = addAttribute(classOf[PositionIncrementAttribute])
  private[this] val lineIter = lines.sortBy(_._1).iterator

  private[this] val emptyTokenStream = new TokenStream {
    override def incrementToken() = false
  }
  private[this] var baseTokenStream = emptyTokenStream

  private[this] var gap = 0
  private[this] var curPos = 0
  private[this] var posLimit = 0
  private[this] var baseTermAttr: CharTermAttribute = null
  private[this] var basePosIncrAttr: PositionIncrementAttribute = null

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
      baseTokenStream.reset
      baseTermAttr =
        if (baseTokenStream.hasAttribute(classOf[CharTermAttribute])) {
          baseTokenStream.getAttribute(classOf[CharTermAttribute])
        } else {
          null
        }
      basePosIncrAttr =
        if (baseTokenStream.hasAttribute(classOf[PositionIncrementAttribute])) {
          baseTokenStream.getAttribute(classOf[PositionIncrementAttribute])
        } else {
          null
        }
      moreToken = baseTokenStream.incrementToken()
    }
    moreToken match {
      case true =>
        termAttr.append(baseTermAttr)
        if (basePosIncrAttr != null) {
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
