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
    val analyzer = DefaultAnalyzer.forIndexingWithStemmer(lang)
    new Field(fieldName, new PhraseTokenStream(fieldName, text, analyzer), Indexable.textFieldTypeNoNorm)
  }
}

// encodes position and the phrase end flag into posIncr. (lucene's payload is too expensive)
// (pos >> 1) gives the real position, (pos & 0x1) == 1 means the phrase end
class PhraseTokenStream(field: String, text: String, analyzer: Analyzer) extends TokenStream {
  private[this] val termAttr = addAttribute(classOf[CharTermAttribute])
  private[this] val posIncrAttr = addAttribute(classOf[PositionIncrementAttribute])

  private[this] var baseTokenStream: TokenStream = null
  private[this] var baseTermAttr: CharTermAttribute = null
  private[this] var more = false
  private[this] var incr = 0
  private[this] var cnt = 0 // token count

  override def incrementToken(): Boolean = {
    if (cnt == 0) {
      // we must lazily instantiate a token stream
      baseTokenStream = analyzer.tokenStream(field, new StringReader(text))
      baseTokenStream.reset()
      more = baseTokenStream.incrementToken()
      baseTermAttr = baseTokenStream.getAttribute(classOf[CharTermAttribute])
      incr = 1 // position before the first token is -1 in Lucene
    }

    if (more) {
      cnt += 1
      termAttr.setEmpty
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
        true
      }
    } else {
      false
    }
  }

  override def reset() {}
}
