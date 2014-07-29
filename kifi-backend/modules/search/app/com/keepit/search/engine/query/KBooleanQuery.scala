package com.keepit.search.engine.query

import org.apache.lucene.index.{ AtomicReaderContext, IndexReader }
import org.apache.lucene.search.{ BooleanClause, BooleanQuery, ComplexExplanation, Explanation, IndexSearcher, Query, Scorer, Weight }
import org.apache.lucene.util.Bits

import java.util.{ List => JList }
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.math._

object KBooleanQuery {
  def apply(clauses: JList[BooleanClause]) = {
    val query = new KBooleanQuery()
    clauses.foreach { clause =>
      if (!clause.getQuery.isInstanceOf[KTextQuery]) {
        throw new KBooleanQueryException(s"wrong subquery class: ${clause.getQuery.getClass().getName()}")
      }
      query.add(clause)
    }
    query
  }
}

class KBooleanQuery() extends BooleanQuery(false) {

  override def rewrite(reader: IndexReader): Query = {
    if (clauses.size() == 1) { // optimize 1-clause queries
      val c = clauses.get(0)
      if (!c.isProhibited()) {
        var query = c.getQuery().rewrite(reader)
        if (getBoost() != 1.0f) {
          // if rewrite was no-op then clone before boost
          if (query eq c.getQuery()) query = query.clone().asInstanceOf[Query]
          query.setBoost(getBoost() * query.getBoost())
        }
        return query
      }
    }

    var returnQuery = this
    val rewrittenQuery = new KBooleanQuery() // recursively rewrite
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
    new BooleanWeight(searcher, false) {
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

      override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
        throw new UnsupportedOperationException()
      }

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

class KBooleanQueryException(msg: String) extends Exception(msg)
