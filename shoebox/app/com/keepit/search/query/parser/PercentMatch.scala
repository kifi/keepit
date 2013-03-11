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

trait PercentMatch extends QueryParser {

  private[this] var percentMatch: Float = 0.0f

  def setPercentMatch(value: Float) { percentMatch = value }

  override protected def getBooleanQuery(clauses: ArrayBuffer[BooleanClause]): Option[Query] = {
    if (clauses.isEmpty) {
      None // all clause words were filtered away by the analyzer.
    } else {
      val query = new BooleanQueryWithPercentMatch(false) // ignore disableCoord. we always enable coord and control the behavior thru a Similarity instance
      query.setPercentMatch(percentMatch)

      val (siteClauses, otherClauses) = clauses.partition{ clause => clause.getQuery.isInstanceOf[SiteQuery] && !clause.isProhibited }
      otherClauses.foreach{ clause => query.add(clause) }

      val finalQuery = if (siteClauses.isEmpty) {
        query
      } else {
        val siteQuery = {
          if (siteClauses.size == 1) siteClauses(0).getQuery
          else siteClauses.foldLeft(new BooleanQuery(true)){ (bq, clause) => bq.add(clause.getQuery, Occur.MUST); bq }
        }

        if (otherClauses.isEmpty) siteQuery
        else new ConditionalQuery(query, siteQuery)
      }
      Option(finalQuery)
    }
  }
}
