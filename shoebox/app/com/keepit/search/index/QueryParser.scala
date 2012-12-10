package com.keepit.search.index

import com.keepit.search.query.BooleanQueryWithPercentMatch
import org.apache.lucene.queryParser.{QueryParser => LuceneQueryParser}
import org.apache.lucene.search.Query
import org.apache.lucene.util.Version
import com.keepit.search.query.ProximityQuery
import com.keepit.search.query.QueryUtil
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.analysis.Analyzer

class QueryParser(analyzer: Analyzer) extends LuceneQueryParser(Version.LUCENE_36, "b", analyzer) {

  def parseQuery(queryText: String) = {
    val query = try {
      if (queryText == null || queryText.trim.length == 0) null
      else super.parse(queryText)
    } catch {
      case e => super.parse(LuceneQueryParser.escape(queryText))
    }
    Option(query)
  }

  private var percentMatch: Float = 0.0f
  def setPercentMatch(value: Float) { percentMatch = value }

  override def newBooleanQuery(disableCoord: Boolean) = {
    val query = new BooleanQueryWithPercentMatch(disableCoord)
    query.setPercentMatch(percentMatch)
    query
  }
}

