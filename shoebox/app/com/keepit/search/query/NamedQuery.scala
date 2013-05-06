package com.keepit.search.query

import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.Term
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.util.Bits
import java.util.{Set => JSet}

class NamedQuery(val name: String, val subQuery: Query, val context: NamedQueryContext) extends Query {

  override def createWeight(searcher: IndexSearcher): Weight = new NamedWeight(this, searcher)

  override def rewrite(reader: IndexReader): Query = {
    val rewritten = subQuery.rewrite(reader)

    if (rewritten eq subQuery) this
    else new NamedQuery(name, rewritten, context)
  }

  override def extractTerms(out: JSet[Term]): Unit = subQuery.extractTerms(out)

  override def toString(s: String) = subQuery.toString(s) // NamedQuery is invisible

  override def equals(obj: Any): Boolean = obj match {
    case query: NamedQuery => (subQuery == query.subQuery)
    case _ => false
  }

  override def setBoost(boost: Float) { throw new UnsupportedOperationException }

  override def hashCode(): Int = subQuery.hashCode()
}

class NamedWeight(query: NamedQuery, searcher: IndexSearcher) extends Weight {

  val subWeight = query.subQuery.createWeight(searcher)

  override def getQuery() = query
  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization() = subWeight.getValueForNormalization

  override def normalize(norm: Float, topLevelBoost: Float) {
    subWeight.normalize(norm, topLevelBoost * query.getBoost())
  }

  override def explain(context: AtomicReaderContext, doc: Int) = subWeight.explain(context, doc) // NamedQuery is invisible

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
    val subScorer = subWeight.scorer(context, true, false, acceptDocs)
    if (subScorer == null) null
    else {
      val namedScorer = new NamedScorer(this, subScorer)
      query.context.setScorer(query.name, namedScorer)
      namedScorer
    }
  }
}

class NamedScorer(weight: Weight, subScorer: Scorer) extends Scorer(weight) with Coordinator {
  private[this] var scoredDoc = -1
  private[this] var savedScore = 0.0f

  override def docID(): Int = subScorer.docID()
  override def nextDoc(): Int = subScorer.nextDoc()
  override def advance(target: Int): Int = subScorer.advance(target)
  override def score() = {
    scoredDoc = subScorer.docID()
    savedScore = subScorer.score()
    savedScore
  }
  override def freq() = 1

  def getScore(doc: Int) = if (doc == scoredDoc) savedScore else 0.0f
}

class NamedScorerWithCoordinator(subWeight: Weight, subScorer: Scorer with Coordinator)
extends NamedScorer(subWeight, subScorer) with Coordinator {
  override def coord: Float = subScorer.coord
}