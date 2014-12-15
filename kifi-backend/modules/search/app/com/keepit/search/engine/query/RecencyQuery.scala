package com.keepit.search.engine.query

import java.lang.{ Float => JFloat }
import java.util.{ Set => JSet }
import org.apache.lucene.index._
import org.apache.lucene.search._
import org.apache.lucene.util.Bits

import scala.math._

class RecencyQuery(val subQuery: Query, val timeStampField: String, val recencyBoostStrength: Float, val halfDecayMillis: Float) extends Query {

  override def createWeight(searcher: IndexSearcher): Weight = new RecencyWeight(this, subQuery.createWeight(searcher))

  override def rewrite(reader: IndexReader): Query = {
    val rewrittenSubQuery = subQuery.rewrite(reader)
    if (subQuery eq rewrittenSubQuery) this else new RecencyQuery(rewrittenSubQuery, timeStampField, recencyBoostStrength, halfDecayMillis)
  }

  override def extractTerms(out: JSet[Term]): Unit = subQuery.extractTerms(out)

  override def toString(s: String) = s"recency(${subQuery.toString(s)}, $halfDecayMillis)"

  override def equals(obj: Any): Boolean = obj match {
    case q: RecencyQuery => (halfDecayMillis == q.halfDecayMillis) && (subQuery == q.subQuery)
    case _ => false
  }

  override def hashCode(): Int = subQuery.hashCode() ^ halfDecayMillis.toInt ^ JFloat.floatToRawIntBits(getBoost())
}

class RecencyWeight(query: RecencyQuery, subWeight: Weight) extends Weight {

  private[this] val currentTimeMillis = System.currentTimeMillis()

  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization() = 1.0f

  override def normalize(norm: Float, topLevelBoost: Float): Unit = {}

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val sc = scorer(context, context.reader.getLiveDocs)
    val exists = (sc != null && sc.advance(doc) == doc)

    val result = new ComplexExplanation()
    if (exists) {
      val recencyScore = sc.score
      result.setDescription(s"recency(${query.halfDecayMillis}), product of:")
      result.setValue(recencyScore)
      result.setMatch(true)
    } else {
      result.setDescription(s"recency(${query.halfDecayMillis}), doesn't match id ${doc}")
      result.setValue(0)
      result.setMatch(false)
    }
    result
  }

  def getQuery(): RecencyQuery = query

  override def scorer(context: AtomicReaderContext, acceptDocs: Bits): Scorer = {
    val subScorer = subWeight.scorer(context, acceptDocs)
    if (subScorer == null) null else {
      val docValues = context.reader.getNumericDocValues(query.timeStampField)
      if (docValues == null) null else {
        new RecencyScorer(this, subScorer, docValues, currentTimeMillis, query.recencyBoostStrength, query.halfDecayMillis)
      }
    }
  }
}

class RecencyScorer(weight: RecencyWeight, subScorer: Scorer, timestampDocValues: NumericDocValues, currentTimeMillis: Long, recencyBoostStrength: Float, halfDecayMillis: Float) extends Scorer(weight) {

  override def docID(): Int = subScorer.docID()

  override def nextDoc(): Int = subScorer.nextDoc()

  override def advance(target: Int): Int = subScorer.advance(target)

  override def score(): Float = {
    val timestamp = timestampDocValues.get(subScorer.docID())
    val t = max(currentTimeMillis - timestamp, 0).toFloat / halfDecayMillis
    val t2 = t * t
    1.0f + (recencyBoostStrength / (1.0f + t2))
  }

  override def freq(): Int = 1
  override def cost(): Long = subScorer.cost()
}
