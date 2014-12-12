package com.keepit.search.engine.query.core

import java.util.{ Set => JSet }

import com.keepit.common.logging.Logging
import org.apache.lucene.index.{ AtomicReaderContext, IndexReader, Term }
import org.apache.lucene.search._
import org.apache.lucene.util.Bits

import scala.collection.mutable.ArrayBuffer

object KTextQuery {
  val tieBreakerMultiplier = 0.5f
}

class KTextQuery(val label: String) extends Query with ProjectableQuery with Logging {

  private var subQuery: Query = new DisjunctionMaxQuery(KTextQuery.tieBreakerMultiplier)

  var terms: Array[Term] = Array()
  var stems: Array[Term] = Array()

  val concatStems: ArrayBuffer[String] = ArrayBuffer()

  private[this] var totalSubQueryCnt: Int = 0

  def addQuery(query: Query, boost: Float = 1.0f): Unit = {
    query.setBoost(boost)
    subQuery = subQuery match {
      case disjunct: DisjunctionMaxQuery =>
        totalSubQueryCnt += 1
        disjunct.add(query)
        disjunct
      case nonDisjunct =>
        // rewrite can optimize DisjunctionMaxQuery away (we don't change query after rewrite, though)
        // put DisjunctionMaxQuery back
        val disjunct = new DisjunctionMaxQuery(KTextQuery.tieBreakerMultiplier)
        disjunct.add(nonDisjunct)
        disjunct.add(query)
        disjunct
    }
  }

  def project(fields: Set[String]) = {
    val projectedQuery = this.clone().asInstanceOf[KTextQuery]
    projectedQuery.subQuery = QueryProjector.project(subQuery, fields)
    projectedQuery
  }

  override def createWeight(searcher: IndexSearcher): Weight = {
    val subWeight = subQuery.createWeight(searcher)
    if (subWeight != null) new KTextWeight(this, subWeight) else null
  }

  override def rewrite(reader: IndexReader): Query = {
    val rewrittenSubQuery = subQuery.rewrite(reader)
    if (subQuery eq rewrittenSubQuery) this
    else {
      val rewritten = this.clone().asInstanceOf[KTextQuery]
      rewritten.subQuery = rewrittenSubQuery
      rewritten
    }
  }

  override def extractTerms(out: JSet[Term]): Unit = { subQuery.extractTerms(out) }

  override def toString(s: String) = {
    s"KTextQuery(${subQuery.toString(s)})"
  }

  override def equals(obj: Any): Boolean = obj match {
    case query: KTextQuery => subQuery.equals(query.subQuery)
    case _ => false
  }

  override def hashCode(): Int = subQuery.hashCode()

  def isEmpty: Boolean = { totalSubQueryCnt == 0 }
}

class KTextWeight(query: KTextQuery, subWeight: Weight) extends Weight with KWeight with Logging {

  override def getQuery() = query
  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization(): Float = {
    val sub = if (subWeight != null) subWeight.getValueForNormalization() else 1.0f
    val boost = query.getBoost()

    (sub * boost * boost)
  }

  override def normalize(norm: Float, topLevelBoost: Float): Unit = {
    subWeight.normalize(norm, topLevelBoost * query.getBoost)
  }

  override def explain(context: AtomicReaderContext, doc: Int): Explanation = {
    val reader = context.reader
    val sc = scorer(context, reader.getLiveDocs);
    val exists = (sc != null && sc.advance(doc) == doc);

    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription("KTextQuery")
      result.setValue(sc.score)
      result.setMatch(true)
      val exp = subWeight.explain(context, doc)
      if (exp.getValue() > 0.0f) result.addDetail(exp)
    } else {
      result.setDescription("KTextQuery, doesn't match id %d".format(doc))
      result.setValue(0.0f)
      result.setMatch(false)
    }
    result
  }

  def getWeights(out: ArrayBuffer[(Weight, Float)]): Unit = {
    out += ((this, 1.0f))
  }

  override def scorer(context: AtomicReaderContext, acceptDocs: Bits): Scorer = {
    subWeight.scorer(context, acceptDocs)
  }
}
