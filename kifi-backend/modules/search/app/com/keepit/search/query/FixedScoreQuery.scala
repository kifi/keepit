package com.keepit.search.query

import org.apache.lucene.index.{ Term, AtomicReaderContext, IndexReader }
import org.apache.lucene.search._
import org.apache.lucene.util.Bits
import java.util.{ Set => JSet }

class FixedScoreQuery(val subQuery: Query) extends Query {

  override def extractTerms(out: JSet[Term]): Unit = {}

  override def rewrite(reader: IndexReader): Query = {
    val rewrittenSub = subQuery.rewrite(reader)
    if (rewrittenSub eq subQuery) this else new FixedScoreQuery(rewrittenSub)
  }

  override def createWeight(searcher: IndexSearcher): Weight = new FixedScoreWeight(this, searcher)

  override def clone(): Query = new FixedScoreQuery(subQuery.clone())

  override def toString(field: String): String = s"FixedScoreQuery(${subQuery.toString(field)})"

  override def hashCode(): Int = {
    "FixedScoreQuery".hashCode() ^ subQuery.hashCode() ^ java.lang.Float.floatToRawIntBits(getBoost())
  }

  override def equals(obj: Any): Boolean = {
    obj match {
      case other: FixedScoreQuery => getBoost == other.getBoost && subQuery.equals(other.subQuery)
      case _ => false
    }
  }
}

class FixedScoreWeight(query: FixedScoreQuery, searcher: IndexSearcher) extends Weight {

  private[this] val subWeight = query.subQuery.createWeight(searcher)

  override def getQuery(): Query = query

  override def getValueForNormalization() = query.getBoost()

  override def normalize(norm: Float, topLevelBoost: Float): Unit = subWeight.normalize(norm, topLevelBoost)

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val sc = scorer(context, true, false, context.reader.getLiveDocs)
    val exists = (sc != null && sc.advance(doc) == doc)

    val result = new ComplexExplanation()
    if (exists) {
      val score = sc.score

      val ret = new ComplexExplanation()
      result.setDescription("fixed score:")
      result.setValue(query.getBoost)
      result.setMatch(true)
    } else {
      result.setDescription("fixed score, doesn't match id %d".format(doc))
      result.setValue(0)
      result.setMatch(false)
    }
    result.addDetail(subWeight.explain(context, doc))
    result
  }

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, liveDocs: Bits): Scorer = {
    val subScorer = subWeight.scorer(context, true, false, liveDocs)
    if (subScorer == null) null else new FixedScoreScorer(this, subScorer, query.getBoost)
  }
}

class FixedScoreScorer(weight: FixedScoreWeight, subScorer: Scorer, scoreVal: Float) extends Scorer(weight) {
  override def docID(): Int = subScorer.docID()
  override def nextDoc(): Int = {
    val doc = subScorer.nextDoc()
    doc
  }
  override def advance(target: Int): Int = {
    val doc = subScorer.advance(target)
    doc
  }
  override def score(): Float = {
    if (docID < DocIdSetIterator.NO_MORE_DOCS) scoreVal else 0.0f
  }
  override def freq(): Int = 1
  override def cost(): Long = subScorer.cost()
}
