package com.keepit.search.index

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttributeImpl
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.util.AttributeImpl
import org.apache.lucene.util.Version
import java.io.Reader

class DefaultAnalyzer extends Analyzer {

  val baseAnalyzer = new StandardAnalyzer(Version.LUCENE_36)
  baseAnalyzer.setMaxTokenLength(256)

  def tokenStream(fieldName: String, reader: Reader): TokenStream = {
    new DotDecompounder(baseAnalyzer.tokenStream(fieldName, reader))
  }
}

class DotDecompounder(tokenStream: TokenStream) extends TokenFilter(tokenStream) {
  val termAttr = addAttribute(classOf[CharTermAttribute])
  val posIncrAttr = addAttribute(classOf[PositionIncrementAttribute])
  val typeAttr = tokenStream.getAttribute(classOf[TypeAttribute]).asInstanceOf[TypeAttributeImpl]
  val tokenType = new TypeAttributeAccessor

  var tokenStart = 0
  var buffer = Array.empty[Char]
  var bufLen = 0

  val alphanum = "<ALPHANUM>"

  override def incrementToken() = {
    if (bufLen - tokenStart > 0) { // has more chars in buffer
      getConstituent
      posIncrAttr.setPositionIncrement(1)
      true
    }
    else {
      if (tokenStream.incrementToken) {
        if (findDotCompound()) getConstituent
        true
      } else {
        false
      }
    }
  }

  private def getConstituent {
    var i = tokenStart
    while (i < bufLen && buffer(i) != '.') i += 1
    termAttr.copyBuffer(buffer, tokenStart, i - tokenStart)
    tokenStart = (i + 1) // skip dot
  }

  private def findDotCompound(): Boolean = {
    tokenStart = 0
    bufLen = 0
    val src = termAttr.buffer
    val len = termAttr.length

    var dotCnt = 0
    var i = 0
    while (i < len) {
      if (src(i) == '.') dotCnt += 1
      i += 1
    }

    if (dotCnt == 0 || dotCnt == len/2) {
      false // regular word or acronym
    } else {
      if (tokenType(typeAttr) == alphanum) {
        i = 0
        while (i < len && src(i) != '.') i += 1

        if (buffer.length < src.length) buffer = new Array[Char](src.length) // resize buffer
        Array.copy(src, 0, buffer, 0, len)
        bufLen = len
        true  // something like a file name
      } else {
        false // probably a number
      }
    }
  }
}

class TypeAttributeAccessor extends TypeAttributeImpl {
  var tokenType: String = TypeAttribute.DEFAULT_TYPE
  override def setType(tt: String) { tokenType = tt }

  def apply(ta: TypeAttributeImpl) = {
    ta.copyTo(this)
    tokenType
  }
}
