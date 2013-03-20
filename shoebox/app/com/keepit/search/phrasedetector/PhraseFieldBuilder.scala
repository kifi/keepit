package com.keepit.search.phrasedetector

import com.keepit.search.Lang
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.Indexable
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.document.Field
import org.apache.lucene.document.FieldType
import org.apache.lucene.document.TextField
import java.io.StringReader
import org.apache.lucene.util.Version

trait PhraseFieldBuilder {
  def buildPhraseField(fieldName: String, text: String, lang: Lang) = {
    val analyzer = DefaultAnalyzer.forIndexingWithStemmer(lang).getOrElse(DefaultAnalyzer.forIndexing(lang)).withFilter[PhraseTokenStream]
    val ts = analyzer.createLazyTokenStream(fieldName, text) // must lazily instantiate a token stream
    new Field(fieldName, ts, Indexable.textFieldTypeNoNorm)
  }
}

// encodes position and the phrase end flag into posIncr. (lucene's payload is too expensive)
// (pos >> 1) gives the real position, (pos & 0x1) == 1 means the phrase end
class PhraseTokenStream(version: Version, baseTokenStream: TokenStream) extends TokenFilter(baseTokenStream) {
  val baseTermAttr = baseTokenStream.getAttribute(classOf[CharTermAttribute])
  val termAttr = addAttribute(classOf[CharTermAttribute])
  val posIncrAttr = addAttribute(classOf[PositionIncrementAttribute])

  baseTokenStream.reset()
  var more = baseTokenStream.incrementToken()
  var incr = 1 // position before the first token is -1 in Lucene
  var cnt = 0 // token count

  override def incrementToken(): Boolean = {
    clearAttributes()
    if (more) {
      cnt += 1
      termAttr.append(baseTermAttr)
      more = baseTokenStream.incrementToken()
      if (cnt == 1 && !more) {
        // this is not a phrase since this is the first token (cnt == 1) and there is no more token
        // don't bother emitting a single token
        false
      } else {
        if (!more) incr += 1
        posIncrAttr.setPositionIncrement(incr)
        incr = 2
      }
      true
    } else {
      false
    }
  }

  override def reset(): Unit = baseTokenStream.reset
}
