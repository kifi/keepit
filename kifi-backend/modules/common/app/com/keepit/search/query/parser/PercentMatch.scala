package com.keepit.search.query.parser

import com.keepit.search.query.BooleanQueryWithPercentMatch
import com.keepit.search.query.SiteQuery
import com.keepit.search.query.ConditionalQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.analysis.Analyzer
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.query.MediaQuery

trait PercentMatch extends QueryParser {

  private[this] var percentMatch: Float = 0.0f

  def setPercentMatch(value: Float) { percentMatch = value }

  override protected def getBooleanQuery(clauses: ArrayBuffer[BooleanClause]): Option[Query] = {
    if (clauses.isEmpty) {
      None // all clause words were filtered away by the analyzer.
    } else {
      val query = new BooleanQueryWithPercentMatch(false) // ignore disableCoord. we always enable coord and control the behavior thru a Similarity instance
      query.setPercentMatch(percentMatch)

      val (specialClauses, otherClauses) = clauses.partition{ clause => (clause.getQuery.isInstanceOf[SiteQuery] || clause.getQuery.isInstanceOf[MediaQuery]) && !clause.isProhibited }
      otherClauses.foreach{ clause => query.add(clause) }

      val finalQuery = if (specialClauses.isEmpty) {
        query
      } else {
        val specialQuery = {
          if (specialClauses.size == 1) specialClauses(0).getQuery
          else specialClauses.foldLeft(new BooleanQuery(true)){ (bq, clause) => bq.add(clause.getQuery, Occur.MUST); bq }
        }

        if (otherClauses.isEmpty) specialQuery
        else new ConditionalQuery(query, specialQuery)
      }
      Option(finalQuery)
    }
  }
}
