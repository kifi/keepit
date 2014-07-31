package com.keepit.search.engine.query

import org.apache.lucene.index.{ AtomicReaderContext, Term }
import org.apache.lucene.search._
import org.apache.lucene.util.{ Bits, ToStringUtils }

import scala.collection.mutable.ArrayBuffer

trait KFilterQuery { self: Query =>

  override def createWeight(searcher: IndexSearcher): Weight = {
    val underlying = this.createWeight(searcher)

    new Weight with KWeight {

      override def explain(context: AtomicReaderContext, doc: Int): Explanation = underlying.explain(context, doc)

      override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
        underlying.scorer(context, scoreDocsInOrder, topScorer, acceptDocs)
      }

      override def getQuery(): Query = underlying.getQuery()

      override def getValueForNormalization(): Float = underlying.getValueForNormalization()

      override def normalize(norm: Float, topLevelBoost: Float): Unit = underlying.normalize(norm, topLevelBoost)

      override def getWeights(out: ArrayBuffer[(Weight, Float)]): Unit = {
        out += ((this, 0.0f))
      }
    }
  }
}

object KSiteQuery {
  def apply(domain: String) = {
    val tok = domain.toLowerCase.dropWhile { c => !c.isLetterOrDigit }
    if (tok.length > 0) {
      new KSiteQuery(new Term("site", tok))
    } else {
      null
    }
  }
}

class KSiteQuery(term: Term) extends TermQuery(term) with KFilterQuery {
  override def toString(s: String) = "site(%s:%s)%s".format(term.field(), term.text(), ToStringUtils.boost(getBoost()))
}

object KMediaQuery {
  // mediaType: e.g. "pdf", "mp3", "movie", "music"
  def apply(mediaType: String) = {
    if (mediaType.length > 0) {
      new KMediaQuery(new Term("media", mediaType))
    } else {
      null
    }
  }
}

class KMediaQuery(term: Term) extends TermQuery(term) with KFilterQuery {
  override def toString(s: String) = "media(%s:%s)%s".format(term.field(), term.text(), ToStringUtils.boost(getBoost()))
}
