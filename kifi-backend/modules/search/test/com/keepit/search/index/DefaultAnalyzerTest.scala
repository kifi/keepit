package com.keepit.search.index

import com.keepit.search.Lang
import org.specs2.mutable._
import org.apache.lucene.analysis.tokenattributes._
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.util.CharArraySet
import java.io.Reader
import java.io.StringReader

class DefaultAnalyzerTest extends Specification {

  case class Token(tokenType: String, tokenText: String, positionIncrement: Int)
  case class HighlightToken(tokenText: String, start: Int, end: Int)

  implicit def toReader(str: String): Reader = new StringReader(str)
  implicit def toToken(a: String) = Token(null, a, 1)
  implicit def toToken(a: (String, Int)) = Token(null, a._1, a._2)
  implicit def toToken(a: (String, String, Int)) = Token(a._1, a._2, a._3)
  implicit def toHighlightToken(a: (String, Int, Int)) = HighlightToken(a._1, a._2, a._3)

  val analyzer = DefaultAnalyzer.getAnalyzer(Lang("en"))
  val analyzerWithStemmer = DefaultAnalyzer.getAnalyzerWithStemmer(Lang("en"))

  "DefaultAnalyzer" should {
    "tokenize a string nicely" in {
      toTokenList(analyzer.tokenStream("b", "DefaultAnalyzer should tokenize a string nicely")) ===
        List[Token](("<ALPHANUM>", "defaultanalyzer", 1),
          ("<ALPHANUM>", "tokenize", 2),
          ("<ALPHANUM>", "string", 2),
          ("<ALPHANUM>", "nicely", 1))
    }

    "break up a dot compound" in {
      toTokenList(analyzer.tokenStream("b", "file.ext")) ===
        List[Token](("<ALPHANUM>", "file", 1), ("<ALPHANUM>", "ext", 1))

      toTokenList(analyzer.tokenStream("b", "a.longer.dot.compound.name")) ===
        List[Token](("<ALPHANUM>", "a", 1),
          ("<ALPHANUM>", "longer", 1),
          ("<ALPHANUM>", "dot", 1),
          ("<ALPHANUM>", "compound", 1),
          ("<ALPHANUM>", "name", 1))

      toTokenList(analyzer.tokenStream("b", "www.yahoo.com")) ===
        List[Token](("<ALPHANUM>", "www", 1),
          ("<ALPHANUM>", "yahoo", 1),
          ("<ALPHANUM>", "com", 1))
    }

    "preserve dots in acronyms" in { // lucene drops the last . in a token
      toTokenList(analyzer.tokenStream("b", "u.s. u.s.a. i.b.m.")) ===
        List[Token](("<ALPHANUM>", "u.s", 1),
          ("<ALPHANUM>", "u.s.a", 1),
          ("<ALPHANUM>", "i.b.m", 1))
    }

    "not tokenize a number" in {
      toTokenList(analyzer.tokenStream("b", "1.2")) === List(Token("<NUM>", "1.2", 1))
      toTokenList(analyzer.tokenStream("b", "1.2.3")) === List(Token("<NUM>", "1.2.3", 1))
    }

    "tokenize a word with stemming" in {
      toTokenList(analyzerWithStemmer.tokenStream("b", "japanese boots O'Reilly's books")) ===
        List[Token](("<ALPHANUM>", "japan", 1),
          ("<ALPHANUM>", "boot", 1),
          ("<ALPHANUM>", "o'reilly", 1),
          ("<ALPHANUM>", "book", 1))
    }

    "tokenize a word with apostrophe as one word in query parsing" in {
      toTokenList(analyzer.tokenStream("b", "O'Reilly's books")) ===
        List[Token](("<ALPHANUM>", "o'reilly's", 1),
          ("<ALPHANUM>", "books", 1))
    }

    "tokenize a word with apostrophe as one word in query parsing (the possesive should be removed)" in {
      toTokenList(analyzerWithStemmer.tokenStream("b", "O'Reilly's books")) ===
        List[Token](("<ALPHANUM>", "o'reilly", 1),
          ("<ALPHANUM>", "book", 1))
    }

    "expose the stop word list" in {
      val stopWords = analyzer.getStopWords

      stopWords must beSome[CharArraySet]
      stopWords.get.contains("scala") === false
      stopWords.get.contains("is") === true
    }

    "tokenize Japanese text" in {
      val ja = DefaultAnalyzer.getAnalyzer(Lang("ja"))

      toJaTokenList(ja.tokenStream("b", "茄子とししとうの煮浸し")) ===
        List[Token]("茄子", ("ししとう", 2), ("煮浸し", 2))
      toJaTokenList(ja.tokenStream("b", "＜日本学術会議＞大震災など緊急事態発生時の対応指針")) ===
        List[Token]("日本", "学術", "会議", "大", ("大震災", 0), "震災", ("緊急", 2), "事態", "発生", "時", ("対応", 2), "指針")
      toJaTokenList(ja.tokenStream("b", "コンピューター")) ===
        List[Token]("コンピュータ")
    }

    "tokenize Japanese text with stemming" in {
      val ja = DefaultAnalyzer.getAnalyzerWithStemmer(Lang("ja"))

      toJaTokenList(ja.tokenStream("b", "＜日本学術会議＞大震災など緊急事態発生時の対応指針")) ===
        List[Token]("ニッポン", "ガクジュツ", "カイギ", "ダイ", ("ダイシンサイ", 0), "シンサイ", ("キンキュウ", 2), "ジタイ", "ハッセイ", "ジ", ("タイオウ", 2), "シシン")
      toJaTokenList(ja.tokenStream("b", "なすの田舎風しょうゆ煮")) ===
        List[Token]("ナス", ("イナカ", 2), "フウ", "ショウユ", "ニ")
    }

    "tokenize Japanese text for highlighting" in {
      val ja = DefaultAnalyzer.getAnalyzerWithStemmer(Lang("ja"))

      toHighlightTokenList(ja.tokenStream("b", "＜日本学術会議＞大震災など緊急事態発生時の対応指針")) ===
        List[HighlightToken](
          ("ニッポン", 1, 3),
          ("ガクジュツ", 3, 5),
          ("カイギ", 5, 7),
          ("ダイ", 8, 9),
          ("ダイシンサイ", 8, 11),
          ("シンサイ", 9, 11),
          ("キンキュウ", 13, 15),
          ("ジタイ", 15, 17),
          ("ハッセイ", 17, 19),
          ("ジ", 19, 20),
          ("タイオウ", 21, 23),
          ("シシン", 23, 25)
        )
    }

  }

  private def toTokenList(ts: TokenStream): List[Token] = {
    val typeAttr = ts.getAttribute(classOf[TypeAttribute]).asInstanceOf[PackedTokenAttributeImpl]
    val termAttr = ts.getAttribute(classOf[CharTermAttribute])
    val posIncrAttr = ts.getAttribute(classOf[PositionIncrementAttribute])

    var ret: List[Token] = Nil
    ts.reset()
    while (ts.incrementToken) {
      ret = Token(typeAttr.`type`(), new String(termAttr.buffer, 0, termAttr.length), posIncrAttr.getPositionIncrement) :: ret
    }
    ts.end()
    ts.close()
    ret.reverse
  }

  private def toJaTokenList(ts: TokenStream): List[Token] = {
    val termAttr = ts.getAttribute(classOf[CharTermAttribute])
    val posIncrAttr = ts.getAttribute(classOf[PositionIncrementAttribute])

    var ret: List[Token] = Nil
    ts.reset()
    while (ts.incrementToken) {
      ret = Token(null, new String(termAttr.buffer, 0, termAttr.length), posIncrAttr.getPositionIncrement) :: ret
    }
    ts.end()
    ts.close()
    ret.reverse
  }

  private def toHighlightTokenList(ts: TokenStream): List[HighlightToken] = {
    var ret: List[HighlightToken] = Nil

    if (ts.hasAttribute(classOf[OffsetAttribute]) && ts.hasAttribute(classOf[CharTermAttribute])) {
      val termAttr = ts.getAttribute(classOf[CharTermAttribute])
      val offsetAttr = ts.getAttribute(classOf[OffsetAttribute])
      ts.reset()
      while (ts.incrementToken()) {
        val termString = new String(termAttr.buffer(), 0, termAttr.length())
        val thisStart = offsetAttr.startOffset()
        val thisEnd = offsetAttr.endOffset()
        ret = HighlightToken(termString, thisStart, thisEnd) :: ret
      }
      ts.end()
      ts.close()
    }
    ret.reverse
  }
}
