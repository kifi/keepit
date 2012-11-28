package com.keepit.search.query

import com.keepit.common.logging.Logging
import com.keepit.search.index.Searcher
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Payload
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.Weight
import org.apache.lucene.util.ReaderUtil
import java.util.{HashSet => JHashSet}
import scala.collection.JavaConversions._

object QueryUtil extends Logging {

  def getTerms(fieldName: String, query: Query): Set[Term] = {
    getTerms(query).filter{ _.field() == fieldName }
  }

  def getTerms(query: Query): Set[Term] = {
    query match {
      case q: TermQuery => fromTermQuery(q)
      case q: PhraseQuery => fromPhraseQuery(q)
      case q: BooleanQuery => fromBooleanQuery(q)
      case q: Query => fromOtherQuery(q)
      case null => Set.empty[Term]
    }
  }

  private def fromTermQuery(query: TermQuery) = Set(query.getTerm)
  private def fromPhraseQuery(query: PhraseQuery) = query.getTerms().toSet
  private def fromBooleanQuery(query: BooleanQuery) = {
    query.getClauses.map{ cl => if (!cl.isProhibited) getTerms(cl.getQuery) else Set.empty[Term] }.reduce{ _ union _ }
  }
  private def fromOtherQuery(query: Query) = {
    try {
      val terms = new JHashSet[Term]()
      query.extractTerms(terms)
      terms.toSet
    } catch {
      case _ =>
        log.warn("term extraction failed: %s".format(query.getClass.toString))
        Set.empty[Term]
    }
  }

  def emptyScorer(weight: Weight) = new Scorer(weight) {
    override def score(): Float = 0.0f
    override def docID() = DocIdSetIterator.NO_MORE_DOCS
    override def nextDoc(): Int = DocIdSetIterator.NO_MORE_DOCS
    override def advance(target: Int): Int = DocIdSetIterator.NO_MORE_DOCS
  }
}
