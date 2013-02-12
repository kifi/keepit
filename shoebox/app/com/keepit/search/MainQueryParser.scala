package com.keepit.search

import com.keepit.search.phrasedetector.PhraseDetector
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.QueryParser
import com.keepit.search.query.QueryUtil._
import com.keepit.search.query.ProximityQuery
import com.keepit.search.query.SemanticVectorQuery
import com.keepit.search.query.TopLevelQuery
import com.keepit.search.query.Coordinator
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.index.Term
import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.inject._
import scala.collection.mutable.ArrayBuffer

class MainQueryParser(analyzer: Analyzer, baseBoost: Float, proximityBoost: Float, semanticBoost: Float, phraseBoost: Float, phraseDetector: PhraseDetector) extends QueryParser(analyzer) {

  super.setAutoGeneratePhraseQueries(true)

  var enableCoord = false

  private[this] val phraseDiscount = (1.0f - phraseBoost)
  private[this] val stemmedSeqs = new ArrayBuffer[Term]
  private[this] val stemmedQuery = new ArrayBuffer[Query]

  override def getFieldQuery(field: String, queryText: String, quoted: Boolean) = {
    field.toLowerCase match {
      case "site" => getSiteQuery(queryText)
      case _ => getTextQuery(queryText, quoted)
    }
  }

  private def getTextQuery(queryText: String, quoted: Boolean) = {
    def copyFieldQuery(query:Query, field: String) = {
      query match {
        case null => null
        case query: TermQuery => copy(query, field)
        case query: PhraseQuery => copy(query, field)
        case _ => super.getFieldQuery(field, queryText, quoted)
      }
    }

    val booleanQuery = new BooleanQuery(true) with Coordinator // add Coordinator trait for TopLevelQuery

    Option(super.getFieldQuery("t", queryText, quoted)).foreach{ query =>
      booleanQuery.add(query, Occur.SHOULD)
      booleanQuery.add(copyFieldQuery(query, "c"), Occur.SHOULD)
      booleanQuery.add(copyFieldQuery(query, "title"), Occur.SHOULD)
    }

    if(!quoted) {
      super.getStemmedFieldQueryOpt("ts", queryText).foreach{ query =>
        val termSeq = getTermSeq("ts", query)
        termSeq.foreach{ term =>
          stemmedSeqs += term
          stemmedQuery += booleanQuery
        }

        booleanQuery.add(query, Occur.SHOULD)
        booleanQuery.add(copyFieldQuery(query, "cs"), Occur.SHOULD)
        booleanQuery.add(copyFieldQuery(query, "title_stemmed"), Occur.SHOULD)
      }
    }

    booleanQuery
  }

  private def tryAddPhraseQueries(query: BooleanQuery) {
    var discountedQuery = Set.empty[Query]
    phraseDetector.detectAll(stemmedSeqs.toArray).foreach{ phrase =>
      val phraseQueries = List("ts", "cs", "title_stemmed").foldLeft(new BooleanQuery()){ (bq, field) =>
        val phraseStart = phrase._1
        val phraseEnd = phraseStart + phrase._2

        // discount subqueries that are deemed as a part of the detected phrase
        var i = phraseStart
        while (i < phraseEnd) {
          val curQuery = stemmedQuery(i)
          // don't discount the same query again
          if (!discountedQuery.contains(curQuery)) curQuery.setBoost(phraseDiscount)
          i += 1
        }

        // construct a phrase query
        val phraseQuery = stemmedSeqs.slice(phraseStart, phraseEnd).foldLeft(new PhraseQuery()){ (phraseQuery, term) =>
          phraseQuery.add(new Term(field, term.text()))
          phraseQuery
        }
        bq.add(phraseQuery, Occur.SHOULD)
        bq
      }
      phraseQueries.setBoost(phraseBoost)
      query.add(phraseQueries, Occur.SHOULD)
    }
  }

  override def parseQuery(queryText: String) = {
    super.parseQuery(queryText).map{ query =>
      val terms = getTerms(query)
      if (terms.size <= 0) query
      else {
        if (phraseBoost > 0.0f) {
          query match {
            case query: BooleanQuery => tryAddPhraseQueries(query)
            case _ =>
          }
        }

        query.setBoost(baseBoost)

        val svq = if (semanticBoost > 0.0f) {
          val svq = SemanticVectorQuery("sv", terms)
          svq.setBoost(semanticBoost)
          Some(svq)
        } else {
          None
        }

        val proxOpt = if (!stemmedSeqs.isEmpty && proximityBoost > 0.0f) {
          val proxQ = new BooleanQuery(true)
          val csterms = stemmedSeqs.map(t => new Term("cs", t.text()))
          val tsterms = stemmedSeqs.map(t => new Term("ts", t.text()))
          val psterms = stemmedSeqs.map(t => new Term("title_stemmed", t.text()))
          proxQ.add(ProximityQuery(csterms), Occur.SHOULD)
          proxQ.add(ProximityQuery(tsterms), Occur.SHOULD)
          proxQ.add(ProximityQuery(psterms), Occur.SHOULD)
          proxQ.setBoost(proximityBoost)
          Some(proxQ)
        } else {
          None
        }

        new TopLevelQuery(query, svq, proxOpt, enableCoord)
      }
    }
  }
}
