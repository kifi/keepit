package com.keepit.search.index

import java.io.IOException
import java.io.Reader
import java.lang.reflect.Constructor
import org.apache.lucene.analysis.ar.ArabicAnalyzer
import org.apache.lucene.analysis.bg.BulgarianAnalyzer
import org.apache.lucene.analysis.cz.CzechAnalyzer
import org.apache.lucene.analysis.el.GreekAnalyzer
import org.apache.lucene.analysis.fa.PersianAnalyzer
import org.apache.lucene.analysis.fr.FrenchAnalyzer
import org.apache.lucene.analysis.hi.HindiAnalyzer
import org.apache.lucene.analysis.id.IndonesianAnalyzer
import org.apache.lucene.analysis.lv.LatvianAnalyzer
import org.apache.lucene.analysis.ro.RomanianAnalyzer
import org.apache.lucene.analysis.snowball.SnowballFilter
import org.apache.lucene.analysis.th.ThaiAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttributeImpl
import org.apache.lucene.analysis.tr.TurkishAnalyzer
import org.apache.lucene.analysis.{Analyzer=>LAnalyzer}
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.core.StopFilter
import org.apache.lucene.analysis.util.CharArraySet
import org.apache.lucene.analysis.util.ElisionFilter
import org.apache.lucene.analysis.util.WordlistLoader
import org.apache.lucene.util.IOUtils
import org.apache.lucene.util.Version
import org.tartarus.snowball.ext.DanishStemmer
import org.tartarus.snowball.ext.DutchStemmer
import org.tartarus.snowball.ext.RomanianStemmer
import org.tartarus.snowball.ext.TurkishStemmer
import org.tartarus.snowball.SnowballProgram
import com.keepit.search.index.LuceneVersion.version
import scala.reflect.ClassTag

trait TokenFilterFactory {
  def apply(tokenSteam: TokenStream): TokenStream
}

object TokenFilterFactories {
  val stopFilter = new StopFilterFactories
  val stemFilter = new StemFilterFactories
}

class StopFilterFactories {
  import LuceneVersion.version

  val Arabic = loadFrom[ArabicAnalyzer]
  val Bulgarian = loadFrom[BulgarianAnalyzer]
  val Czech = loadFrom[CzechAnalyzer]
  val Danish = loadFromSnowball("danish_stop.txt")
  val Dutch = loadFromSnowball("dutch_stop.txt")
  val Greek = loadFrom[GreekAnalyzer]
  val English = loadFromSnowball("english_stop.txt")
  val Finnish = loadFromSnowball("finnish_stop.txt")
  val French = loadFromSnowball("french_stop.txt")
  val German = loadFromSnowball("german_stop.txt")
  val Hindi = loadFrom[HindiAnalyzer]
  val Hungarian = loadFromSnowball("hungarian_stop.txt")
  val Indonesian = loadFrom[IndonesianAnalyzer]
  val Italian = loadFromSnowball("italian_stop.txt")
  val Latvian = loadFrom[LatvianAnalyzer]
  val Norwegian = loadFromSnowball("norwegian_stop.txt")
  val Persian = loadFrom[PersianAnalyzer]
  val Portuguese = loadFromSnowball("portuguese_stop.txt")
  val Romanian = loadFrom[RomanianAnalyzer]
  val Russian = loadFromSnowball("russian_stop.txt")
  val Spanish = loadFromSnowball("spanish_stop.txt")
  val Swedish = loadFromSnowball("swedish_stop.txt")
  val Thai = loadFrom[ThaiAnalyzer]
  val Turkish = loadFrom[TurkishAnalyzer]

  private def loadFromSnowball(stopWordFile: String): TokenFilterFactory = {
    var reader: Reader = null
    try {
      reader = IOUtils.getDecodingReader(classOf[SnowballFilter], stopWordFile, IOUtils.CHARSET_UTF_8)
      val stopSet = WordlistLoader.getSnowballWordSet(reader, Version.LUCENE_41)
      load(stopSet)
    } catch {
      case ex: IOException =>
      // default set should always be present as it is part of the distribution (JAR)
      throw new RuntimeException("Unable to load default stopword set");
    } finally {
      IOUtils.close(reader)
    }
  }

  private def loadFrom[A <: LAnalyzer](implicit m : ClassTag[A]): TokenFilterFactory = loadFrom[A](false, "stopwords.txt", "#")

  private def loadFrom[A](ignoreCase: Boolean, file: String, comment: String)(implicit m : ClassTag[A]): TokenFilterFactory = {
    var reader: Reader = null
    try {
      val file = "stopwords.txt"
      val clazz = m.runtimeClass
      reader = IOUtils.getDecodingReader(clazz.getResourceAsStream(file), IOUtils.CHARSET_UTF_8)
      val stopSet = WordlistLoader.getWordSet(reader, comment, new CharArraySet(version, 16, ignoreCase))
      load(stopSet)
    } catch {
      case ex: IOException =>
        // default set should always be present as it is part of the distribution (JAR)
        throw new RuntimeException("Unable to load default stopword set");
    } finally {
      IOUtils.close(reader)
    }
  }

  private def load(stopSet: CharArraySet) = {
    new TokenFilterFactory {
      def apply(tokenStream: TokenStream) = new StopFilter(version, tokenStream, stopSet)
    }
  }
}

class StemFilterFactories {
  val Danish = snowball[DanishStemmer]
  val Dutch = snowball[DutchStemmer]
  val Romanian = snowball[RomanianStemmer]
  val Turkish = snowball[TurkishStemmer]

  private def snowball[S <: SnowballProgram](implicit m : ClassTag[S]) = {
    val snowballConstructor = m.runtimeClass.getConstructor().asInstanceOf[Constructor[SnowballProgram]]
    new TokenFilterFactory {
      def apply(tokenStream: TokenStream) = new SnowballFilter(tokenStream, snowballConstructor.newInstance())
    }
  }
}

object WrapperTokenFilterFactory {
  def apply(constructor: Constructor[TokenStream]) = new TokenFilterFactory {
    def apply(tokenStream: TokenStream) = constructor.newInstance(tokenStream)
  }
  def apply(constructor: Constructor[TokenStream], version: Version) = new TokenFilterFactory {
    def apply(tokenStream: TokenStream) = constructor.newInstance(version, tokenStream)
  }
}

class FrenchElisionFilter(tokenStream: TokenStream)
extends TokenFilter(new ElisionFilter(tokenStream, FrenchAnalyzer.DEFAULT_ARTICLES)) {
  override def incrementToken() = input.incrementToken()
}

class SymbolDecompounder(tokenStream: TokenStream) extends TokenFilter(tokenStream) {
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
    while (i < bufLen && buffer(i) != '.' && buffer(i) != '_') i += 1
    termAttr.copyBuffer(buffer, tokenStart, i - tokenStart)
    tokenStart = (i + 1) // skip symbol
  }

  private def findDotCompound(): Boolean = {
    tokenStart = 0
    bufLen = 0
    val src = termAttr.buffer
    val len = termAttr.length

    var cnt = 0
    var i = 0
    while (i < len) {
      if (src(i) == '.' || src(i) == '_') cnt += 1
      i += 1
    }

    if (cnt == 0 || cnt == len/2) {
      false // regular word or acronym
    } else {
      if (tokenType(typeAttr) == alphanum) {

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

class ApostropheFilter(tokenStream: TokenStream) extends TokenFilter(tokenStream) {
  val termAttr = addAttribute(classOf[CharTermAttribute])

  override def incrementToken() = {
    if (tokenStream.incrementToken) {
      val src = termAttr.buffer
      val len = termAttr.length
      val c = termAttr.charAt(termAttr.length - 1)
      if (c == '\'') termAttr.setLength(termAttr.length - 1)
      true
    } else {
      false
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

