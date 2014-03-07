package com.keepit.search.index

import com.keepit.search.Lang
import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttributeImpl
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.util.CharArraySet
import org.apache.lucene.util.Version
import java.io.Reader
import java.io.StringReader

class DefaultAnalyzerTest extends Specification {

  implicit def toReader(str: String): Reader = new StringReader(str)
  val analyzer = DefaultAnalyzer.forIndexing
  val analyzerWithStemmer = DefaultAnalyzer.forIndexingWithStemmer

  "DefaultAnalyzer" should {
    "tokenize a string nicely" in {
      toTokenList(analyzer.tokenStream("b", "DefaultAnalyzer should tokenize a string nicely")) ===
        List(Token("<ALPHANUM>", "defaultanalyzer", 1),
             Token("<ALPHANUM>", "tokenize", 1),
             Token("<ALPHANUM>", "string", 1),
             Token("<ALPHANUM>", "nicely", 1))
    }

    "break up a dot compound" in {
      toTokenList(analyzer.tokenStream("b", "file.ext")) ===
        List(Token("<ALPHANUM>", "file", 1), Token("<ALPHANUM>", "ext", 1))

      toTokenList(analyzer.tokenStream("b", "a.longer.dot.compound.name")) ===
        List(Token("<ALPHANUM>", "a", 1),
             Token("<ALPHANUM>", "longer", 1),
             Token("<ALPHANUM>", "dot", 1),
             Token("<ALPHANUM>", "compound", 1),
             Token("<ALPHANUM>", "name", 1))

      toTokenList(analyzer.tokenStream("b", "www.yahoo.com")) ===
        List(Token("<ALPHANUM>", "www", 1),
             Token("<ALPHANUM>", "yahoo", 1),
             Token("<ALPHANUM>", "com", 1))
    }

    "preserve dots in acronyms" in { // lucene drops the last . in a token
      toTokenList(analyzer.tokenStream("b", "u.s. u.s.a. i.b.m.")) ===
        List(Token("<ALPHANUM>", "u.s", 1),
             Token("<ALPHANUM>", "u.s.a", 1),
             Token("<ALPHANUM>", "i.b.m", 1))
    }

    "not tokenize a number" in {
      toTokenList(analyzer.tokenStream("b", "1.2")) === List(Token("<NUM>", "1.2", 1))
      toTokenList(analyzer.tokenStream("b", "1.2.3")) === List(Token("<NUM>", "1.2.3", 1))
    }

    "tokenize a word with stemming" in {
      toTokenList(analyzerWithStemmer.tokenStream("b", "japanese boots O'Reilly's books")) ===
        List(Token("<ALPHANUM>", "japan", 1),
             Token("<ALPHANUM>", "boot", 1),
             Token("<ALPHANUM>", "o'reilly", 1),
             Token("<ALPHANUM>", "book", 1))
    }

    "tokenize a word with apostrophe as one word in query parsing" in {
      toTokenList(DefaultAnalyzer.forParsing.tokenStream("b", "O'Reilly's books")) ===
        List(Token("<ALPHANUM>", "o'reilly's", 1),
             Token("<ALPHANUM>", "books", 1))
    }

    "tokenize a word with apostrophe as one word in query parsing (the possesive should be removed)" in {
      toTokenList(DefaultAnalyzer.forParsingWithStemmer.tokenStream("b", "O'Reilly's books")) ===
        List(Token("<ALPHANUM>", "o'reilly", 1),
             Token("<ALPHANUM>", "book", 1))
    }

    "expose the stop word list" in {
      val stopWords = DefaultAnalyzer.forIndexing.getStopWords

      stopWords must beSome[CharArraySet]
      stopWords.get.contains("scala") === false
      stopWords.get.contains("is") === true
    }

    "tokenize Japanese text" in {
      val ja = DefaultAnalyzer.forIndexing(Lang("ja"))
      toJaTokenList(ja.tokenStream("b", "茄子とししとうの煮浸し")).map(_.tokenText) ===
        List("茄子", "ししとう", "煮浸し")
      toJaTokenList(ja.tokenStream("b", "コンピューター")).map(_.tokenText) ===
        List("コンピュータ")
    }

    "tokenize Japanese text with stemming" in {
      val ja = DefaultAnalyzer.forIndexingWithStemmer(Lang("ja"))
      toJaTokenList(ja.tokenStream("b", "茄子とししとうの煮浸し")).map(_.tokenText) ===
        List("ナスビ", "シシトウ", "ニビタシ")
    }
  }

  def toTokenList(ts: TokenStream) = {
    val typeAttr = ts.getAttribute(classOf[TypeAttribute]).asInstanceOf[TypeAttributeImpl]
    val termAttr = ts.getAttribute(classOf[CharTermAttribute])
    val posIncrAttr = ts.getAttribute(classOf[PositionIncrementAttribute])
    val typeAcc = new TypeAttributeAccessor

    var ret: List[Token] = Nil
    ts.reset()
    while (ts.incrementToken) {
      ret = Token(typeAcc(typeAttr), new String(termAttr.buffer, 0, termAttr.length), posIncrAttr.getPositionIncrement) :: ret
    }
    ret.reverse
  }

  case class Token(tokenType: String, tokenText: String, positionIncrement: Int)

  def toJaTokenList(ts: TokenStream) = {
    val termAttr = ts.getAttribute(classOf[CharTermAttribute])
    val posIncrAttr = ts.getAttribute(classOf[PositionIncrementAttribute])
    val typeAcc = new TypeAttributeAccessor

    var ret: List[Token] = Nil
    ts.reset()
    while (ts.incrementToken) {
      ret = Token(null, new String(termAttr.buffer, 0, termAttr.length), posIncrAttr.getPositionIncrement) :: ret
    }
    ret.reverse
  }
}
