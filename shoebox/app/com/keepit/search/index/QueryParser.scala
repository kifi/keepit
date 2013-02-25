package com.keepit.search.index

import com.keepit.search.query.BooleanQueryWithPercentMatch
import com.keepit.search.query.ProximityQuery
import com.keepit.search.query.QueryUtil
import com.keepit.search.query.SiteQuery
import com.keepit.search.query.ConditionalQuery
import org.apache.lucene.queryParser.{QueryParser => LuceneQueryParser}
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanClause._
import org.apache.lucene.search.TermQuery
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.index.Term
import org.apache.lucene.util.Version
import java.util.{List => JList}
import scala.collection.JavaConversions._

class QueryParser(analyzer: Analyzer) extends LuceneQueryParser(Version.LUCENE_36, "b", analyzer) {

  private[this] var booleanUsed = false
  def isMultiClauseQuery = booleanUsed

  def parseQuery(queryText: String) = {
    val query = try {
      if (queryText == null || queryText.trim.length == 0) null
      else super.parse(queryText)
    } catch {
      case e: Throwable => super.parse(LuceneQueryParser.escape(queryText))
    }
    Option(query)
  }

  private[this] var percentMatch: Float = 0.0f
  def setPercentMatch(value: Float) { percentMatch = value }

  override protected def getBooleanQuery(clauses: JList[BooleanClause], disableCoord: Boolean) = {
    if (clauses.size ==0) {
      null; // all clause words were filtered away by the analyzer.
    }
    val query = new BooleanQueryWithPercentMatch(false) // ignore disableCoord. we always enable coord and control the behavior thru a Similarity instance
    query.setPercentMatch(percentMatch)

    val (siteClauses, otherClauses) = clauses.partition{ clause => clause.getQuery.isInstanceOf[SiteQuery] && !clause.isProhibited }
    otherClauses.foreach{ clause => query.add(clause) }

    if (siteClauses.isEmpty) {
      reduceBooleanQueryWithPercentMatch(query)
    } else {
      val siteQuery = {
        if (siteClauses.size == 1) siteClauses(0).getQuery
        else siteClauses.foldLeft(new BooleanQuery(true)){ (bq, clause) => bq.add(clause.getQuery, Occur.MUST); bq }
      }

      if (otherClauses.isEmpty) siteQuery
      else new ConditionalQuery(reduceBooleanQueryWithPercentMatch(query), siteQuery)
    }
  }

  private[this] def reduceBooleanQueryWithPercentMatch(query: BooleanQuery) = {
    val clauses = query.getClauses
    if (clauses.length == 1) {
      clauses(0).getQuery
    } else {
      booleanUsed = true
      query
    }
  }

  def getSiteQuery(domain: String) = if (domain != null) SiteQuery(domain) else null

  private[this] var stemmedQueryBuilder: Option[StemmedQueryBuilder] = None
  def setStemmingAnalyzer(stemmingAnalyzer: Analyzer) {
    stemmedQueryBuilder = Some(new StemmedQueryBuilder(stemmingAnalyzer))
  }

  def getStemmedFieldQueryOpt(fieldName: String, queryText: String): Option[Query] = {
    stemmedQueryBuilder.flatMap{ builder => builder(fieldName, queryText) }
  }

  class StemmedQueryBuilder(analyzer: Analyzer) extends LuceneQueryParser(Version.LUCENE_36, "b", analyzer) {
    super.setAutoGeneratePhraseQueries(true)
    def apply(fieldName: String, term: String) = Option(getFieldQuery(fieldName, term, false))
  }

  // disabling WildcardQuery, PrefixQuery, FuzzyQuery, RangeQuery
  override def getWildcardQuery(field: String, termStr: String) = getFieldQuery(field, termStr, false)
  override def getPrefixQuery(field: String, termStr: String) = getFieldQuery(field, termStr, false)
  override def getFuzzyQuery(field: String, termStr: String, minSimilarity: Float) = getFieldQuery(field, termStr, false)
  override def getRangeQuery(field: String, part1: String, part2: String, inclusive: Boolean) = getFieldQuery(field, "%s %s".format(part1, part2), false)
}

