package com.keepit.search.index

import com.keepit.search.Lang
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.{ CharTermAttribute, PositionIncrementAttribute }
import org.apache.lucene.document.{ Field, FieldType, TextField }

object LineField {
  val MAX_POSITION_PER_LINE = 2048
  val LINE_GAP = 3

  val emptyTokenStream = new TokenStream {
    override def incrementToken() = false
  }

}

trait LineFieldBuilder {
  def buildLineField[T](fieldName: String, lines: Seq[(Int, T, Lang)], fieldType: FieldType = TextField.TYPE_NOT_STORED)(tokenStreamFunc: (String, T, Lang) => TokenStream): Field = {
    new Field(fieldName, new LineTokenStream(fieldName, lines, tokenStreamFunc), fieldType)
  }
}

class LineTokenStream[T](fieldName: String, lines: Seq[(Int, T, Lang)], tokenStreamFunc: (String, T, Lang) => TokenStream) extends TokenStream {
  private[this] val termAttr = addAttribute(classOf[CharTermAttribute])
  private[this] val posIncrAttr = addAttribute(classOf[PositionIncrementAttribute])
  private[this] val lineIter = lines.sortBy(_._1).iterator

  private[this] var baseTokenStream = LineField.emptyTokenStream

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
      val (lineNo, text, lang) = lineIter.next
      val (lineStart, lineEnd) = lineRange(lineNo)
      incr = lineStart - curPos
      posLimit = lineEnd - 1
      baseTokenStream.end()
      baseTokenStream.close()
      baseTokenStream = tokenStreamFunc(fieldName, text, lang)
      baseTokenStream.reset()
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

  override def end(): Unit = baseTokenStream.end()
  override def close(): Unit = baseTokenStream.close()
}
