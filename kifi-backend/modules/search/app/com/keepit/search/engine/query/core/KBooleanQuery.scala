package com.keepit.search.engine.query.core

import org.apache.lucene.index.{ AtomicReaderContext, IndexReader }
import org.apache.lucene.search.{ BooleanQuery, ComplexExplanation, Explanation, IndexSearcher, Query, Weight }

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.math._

class KBooleanQuery() extends BooleanQuery(false) with ProjectableQuery {

  def project(fields: Set[String]): Query = {
    val projectedQuery = new KBooleanQuery()
    getClauses.foreach { c =>
      val query = QueryProjector.project(c.getQuery, fields)
      if (query != null) projectedQuery.add(query, c.getOccur)
    }
    projectedQuery.setBoost(getBoost())
    projectedQuery
  }

  override def rewrite(reader: IndexReader): Query = {
    var returnQuery = this
    val rewrittenQuery = new KBooleanQuery() // recursively rewrite
    rewrittenQuery.setBoost(getBoost())
    getClauses.foreach { c =>
      val query = c.getQuery.rewrite(reader)

      if (query eq c.getQuery) rewrittenQuery.add(c)
      else {
        rewrittenQuery.add(query, c.getOccur)
        returnQuery = rewrittenQuery
      }
    }
    returnQuery
  }

  override def clone(): BooleanQuery = {
    val clone = new KBooleanQuery()
    clauses.foreach { c => clone.add(c) }
    clone.setBoost(getBoost)
    clone.setMinimumNumberShouldMatch(getMinimumNumberShouldMatch)
    clone
  }

  override def createWeight(searcher: IndexSearcher) = {
    new BooleanWeight(searcher, false) with KWeight {
      private[this] val weightList = new ArrayBuffer[(Weight, Float)]
      private[this] val normalizationValue: Float = {
        var sum = 0.0d
        clauses.zip(weights).foreach {
          case (c, w) =>
            if (c.isProhibited()) {
              weightList += ((w, 0.0f)) // weight = 0 since a prohibited should not be counted in percent match
            } else {
              val value = w.getValueForNormalization().toDouble
              val sqrtValue = sqrt(value).toFloat
              weightList += ((w, sqrtValue))
              sum += value
            }
        }
        (sum.toFloat * getBoost() * getBoost())
      }

      override def getValueForNormalization(): Float = normalizationValue

      def getWeights(out: ArrayBuffer[(Weight, Float)]): Unit = {
        out ++= weightList
      }

      override def explain(context: AtomicReaderContext, doc: Int): Explanation = {

        val sumExpl = new ComplexExplanation()
        var sum = 0.0f

        clauses.zip(weightList).foreach {
          case (clause, (w, value)) =>
            val e = w.explain(context, doc)
            if (e.isMatch()) {
              sumExpl.addDetail(e)
              if (!clause.isProhibited) sum += e.getValue()
            }
        }
        sumExpl.setMatch(sum > 0.0f)
        sumExpl.setValue(sum)

        sumExpl.setDescription(s"KBooleanQuery, sum of:")
        sumExpl
      }
    }
  }

  override def equals(obj: Any): Boolean = {
    obj match {
      case other: KBooleanQuery =>
        getBoost == other.getBoost &&
          clauses.equals(other.clauses) &&
          getMinimumNumberShouldMatch == other.getMinimumNumberShouldMatch
      case _ => false
    }
  }
}
