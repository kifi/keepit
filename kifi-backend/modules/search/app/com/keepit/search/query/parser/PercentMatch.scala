package com.keepit.search.query.parser

import com.keepit.search.query.BooleanQueryWithPercentMatch
import com.keepit.search.query.ConditionalQuery
import com.keepit.search.query.MediaQuery
import com.keepit.search.query.SiteQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.search.Filter
import scala.collection.mutable.ArrayBuffer
import com.keepit.search.query.BooleanQueryWithSemanticMatch

trait PercentMatch extends QueryParser {

  val useSemanticMatch = false

  private[this] var percentMatch: Float = 0.0f
  private[this] var percentMatchForHotDocs: Float = 1.0f
  private[this] var hotDocFilter: Option[Filter] = None

  def setPercentMatch(value: Float): Unit = {
    percentMatch = value
  }

  def setPercentMatchForHotDocs(value: Float, hotDocs: Filter): Unit = {
    percentMatchForHotDocs = value
    hotDocFilter = Option(hotDocs)
  }

  override protected def getBooleanQuery(clauses: ArrayBuffer[BooleanClause]): Option[Query] = {
    if (clauses.isEmpty) {
      None // all clause words were filtered away by the analyzer.
    } else {
      val query = if (useSemanticMatch) new BooleanQueryWithSemanticMatch(false) else new BooleanQueryWithPercentMatch(false)
      query.setPercentMatch(percentMatch)
      hotDocFilter.foreach { f => query.setPercentMatchForHotDocs(percentMatchForHotDocs, f) }

      val (specialClauses, otherClauses) = clauses.partition { clause => (clause.getQuery.isInstanceOf[SiteQuery] || clause.getQuery.isInstanceOf[MediaQuery]) && !clause.isProhibited }
      otherClauses.foreach { clause => query.add(clause) }

      val finalQuery = if (specialClauses.isEmpty) {
        query
      } else {
        val specialQuery = {
          if (specialClauses.size == 1) specialClauses(0).getQuery
          else specialClauses.foldLeft(new BooleanQuery(true)) { (bq, clause) => bq.add(clause.getQuery, Occur.MUST); bq }
        }

        if (otherClauses.isEmpty) specialQuery
        else new ConditionalQuery(query, specialQuery)
      }
      Option(finalQuery)
    }
  }
}
