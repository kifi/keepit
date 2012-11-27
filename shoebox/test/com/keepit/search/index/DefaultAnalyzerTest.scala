package com.keepit.search.index

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttributeImpl
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.util.Version
import java.io.Reader
import java.io.StringReader

@RunWith(classOf[JUnitRunner])
class DefaultAnalyzerTest extends SpecificationWithJUnit {

  implicit def toReader(str: String): Reader = new StringReader(str)
  val analyzer = DefaultAnalyzer.forIndexing

  "DefaultAnalyzer" should {
    "tokenize a string nicely" in {
      toTokenList(analyzer.tokenStream("b", "DefaultAnalyzer should tokenize a string nicely")) ===
        List(Token("<ALPHANUM>", "defaultanalyzer", 1),
             Token("<ALPHANUM>", "should", 1),
             Token("<ALPHANUM>", "tokenize", 1),
             Token("<ALPHANUM>", "string", 2),
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

    "tokenize a word with apostrophe and place the original and constituents at the same position in indexing" in {
      toTokenList(analyzer.tokenStream("b", "O'Reilly's books you're interested")) ===
        List(Token("<ALPHANUM>", "o'reilly's", 1),
             Token("<ALPHANUM>", "o", 0),
             Token("<ALPHANUM>", "reilly", 0),
             Token("<ALPHANUM>", "s", 0),
             Token("<ALPHANUM>", "books", 1),
             Token("<ALPHANUM>", "you're", 1),
             Token("<ALPHANUM>", "you", 0),
             Token("<ALPHANUM>", "re", 0),
             Token("<ALPHANUM>", "interested", 1))
    }

    "tokenize a word with apostrophe as one work in query parsing" in {
      toTokenList(DefaultAnalyzer.forParsing.tokenStream("b", "O'Reilly's books you're interested")) ===
        List(Token("<ALPHANUM>", "o'reilly's", 1),
             Token("<ALPHANUM>", "books", 1),
             Token("<ALPHANUM>", "you're", 1),
             Token("<ALPHANUM>", "interested", 1))
    }

  }

  def toTokenList(ts: TokenStream) = {
    val typeAttr = ts.getAttribute(classOf[TypeAttribute]).asInstanceOf[TypeAttributeImpl]
    val termAttr = ts.getAttribute(classOf[CharTermAttribute])
    val posIncrAttr = ts.getAttribute(classOf[PositionIncrementAttribute])
    val typeAcc = new TypeAttributeAccessor

    var ret: List[Token] = Nil
    while (ts.incrementToken) {
      ret = Token(typeAcc(typeAttr), new String(termAttr.buffer, 0, termAttr.length), posIncrAttr.getPositionIncrement) :: ret
    }
    ret.reverse
  }

  case class Token(tokenText: String, tokenType: String, positionIncrement: Int)
}
