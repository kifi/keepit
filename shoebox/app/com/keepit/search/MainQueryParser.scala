package com.keepit.search

import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.QueryParser
import com.keepit.search.query.QueryUtil
import com.keepit.search.query.ProximityQuery
import com.keepit.search.query.SemanticVectorQuery
import com.keepit.search.query.TopLevelQuery
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.analysis.Analyzer
import com.keepit.search.query.Coordinator

object MainQueryParser {

  def apply(lang: Lang, proximityBoost: Float, semanticBoost: Float): MainQueryParser = {
    val total = 1.0f + proximityBoost + semanticBoost
    val parser = new MainQueryParser(DefaultAnalyzer.forParsing(lang), 1.0f/total, proximityBoost/total, semanticBoost/total)
    DefaultAnalyzer.forParsingWithStemmer(lang).foreach{ parser.setStemmingAnalyzer(_) }
    parser
  }
}

class MainQueryParser(analyzer: Analyzer, baseBoost: Float, proximityBoost: Float, semanticBoost: Float) extends QueryParser(analyzer) {

  super.setAutoGeneratePhraseQueries(true)

  var enableCoord = false

  override def getFieldQuery(field: String, queryText: String, quoted: Boolean) = {
    field.toLowerCase match {
      case "site" => getSiteQuery(queryText)
      case _ => getTextQuery(queryText, quoted)
    }
  }

  private def getTextQuery(queryText: String, quoted: Boolean) = {
    val booleanQuery = new BooleanQuery(true) with Coordinator // add Coordinator trait for TopLevelQuery

    var query = super.getFieldQuery("t", queryText, quoted)
    if (query != null) booleanQuery.add(query, Occur.SHOULD)

    query = super.getFieldQuery("c", queryText, quoted)
    if (query != null) booleanQuery.add(query, Occur.SHOULD)

    query = super.getFieldQuery("title", queryText, quoted)
    if (query != null) booleanQuery.add(query, Occur.SHOULD)

    if(!quoted) {
      super.getStemmedFieldQueryOpt("ts", queryText).foreach{ query => booleanQuery.add(query, Occur.SHOULD) }
      super.getStemmedFieldQueryOpt("cs", queryText).foreach{ query => booleanQuery.add(query, Occur.SHOULD) }
      super.getStemmedFieldQueryOpt("title_stemmed", queryText).foreach{ query => booleanQuery.add(query, Occur.SHOULD) }
    }

    val clauses = booleanQuery.clauses
    if (clauses.size == 0) null
    else if (clauses.size == 1) clauses.get(0).getQuery()
    else booleanQuery
  }

  override def parseQuery(queryText: String) = {
    super.parseQuery(queryText).map{ query =>
      val terms = QueryUtil.getTerms(query)
      if (terms.size <= 0) query
      else {
        query.setBoost(baseBoost)

        val svq = SemanticVectorQuery("sv", terms)
        svq.setBoost(semanticBoost)

        val proxQ = new BooleanQuery(true)
        val csterms = QueryUtil.getTermSeq("cs", query)
        val tsterms = QueryUtil.getTermSeq("ts", query)
        val psterms = QueryUtil.getTermSeq("title_stemmed", query)
        if (csterms.size > 1) proxQ.add(ProximityQuery(csterms), Occur.SHOULD)
        if (tsterms.size > 1) proxQ.add(ProximityQuery(tsterms), Occur.SHOULD)
        if (psterms.size > 1) proxQ.add(ProximityQuery(psterms), Occur.SHOULD)
        val clauses = proxQ.getClauses()
        val proxOpt = if (clauses.length == 1) {
          Some(clauses(0).getQuery)
        } else if (clauses.length > 1) {
          Some(proxQ)
        } else {
          None
        }
        new TopLevelQuery(query, svq, proxOpt, enableCoord)
      }
    }
  }
}
