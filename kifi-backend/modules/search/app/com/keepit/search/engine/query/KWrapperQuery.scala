package com.keepit.search.engine.query

import com.keepit.common.logging.Logging
import org.apache.lucene.index.{ IndexReader, Term, AtomicReaderContext }
import org.apache.lucene.search._
import org.apache.lucene.util.Bits
import scala.collection.mutable.ArrayBuffer
import java.util.{ Set => JSet }

class KWrapperQuery(private val subQuery: Query, val label: String) extends Query with ProjectableQuery with Logging {
  def this(subQuery: Query) = this(subQuery, subQuery.toString)

  def project(fields: Set[String]): Query = {
    val q = project(subQuery, fields)
    new KWrapperQuery(if (q != null) q else new NullQuery, label)
  }

  override def createWeight(searcher: IndexSearcher): Weight = {
    new KWrapperWeight(this, subQuery.createWeight(searcher))
  }

  override def rewrite(reader: IndexReader): Query = {
    val rewrittenQuery = subQuery.rewrite(reader)
    if (subQuery eq rewrittenQuery) this else new KWrapperQuery(rewrittenQuery)
  }

  override def extractTerms(out: JSet[Term]): Unit = subQuery.extractTerms(out)

  override def toString(s: String) = subQuery.toString(s)

  override def equals(obj: Any): Boolean = obj match {
    case query: KWrapperQuery => (subQuery.equals(query.subQuery))
    case _ => false
  }

  override def hashCode(): Int = subQuery.hashCode()
}

class KWrapperWeight(query: KWrapperQuery, subWeight: Weight) extends Weight with KWeight with Logging {

  override def getQuery() = query
  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization(): Float = subWeight.getValueForNormalization()

  override def normalize(norm: Float, topLevelBoost: Float): Unit = subWeight.normalize(norm, topLevelBoost)

  override def explain(context: AtomicReaderContext, doc: Int): Explanation = subWeight.explain(context, doc)

  def getWeights(out: ArrayBuffer[(Weight, Float)]): Unit = {
    out += ((this, 1.0f))
  }

  override def scorer(context: AtomicReaderContext, acceptDocs: Bits): Scorer = {
    subWeight.scorer(context, acceptDocs)
  }
}
