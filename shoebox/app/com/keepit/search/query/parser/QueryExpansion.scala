package com.keepit.search.query.parser

import com.keepit.classify.Domain
import com.keepit.search.query.Coordinator
import com.keepit.search.query.QueryUtil._
import com.keepit.search.query.SiteQuery
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanClause.Occur._
import scala.collection.mutable.ArrayBuffer

trait QueryExpansion extends QueryParser {

  var enableCoord = false
  val siteBoost: Float

  private[this] val stemmedTerms = new ArrayBuffer[Term]

  def hasStemmedTerms = !stemmedTerms.isEmpty

  def numStemmedTerms = stemmedTerms.size

  def getStemmedTermArray = stemmedTerms.toArray

  def getStemmedTerms(field: String) = stemmedTerms.map(t => new Term(field, t.text()))

  def getStemmedPhrase(field: String, phraseStart: Int, phraseEnd: Int) = {
    stemmedTerms.slice(phraseStart, phraseEnd).foldLeft(new PhraseQuery()){ (phraseQuery, term) =>
      phraseQuery.add(new Term(field, term.text()))
      phraseQuery
    }
  }

  override def getFieldQuery(field: String, queryText: String, quoted: Boolean): Option[Query] = {
    field.toLowerCase match {
      case "site" => getSiteQuery(queryText)
      case _ => getTextQuery(queryText, quoted)
    }
  }

  protected def getSiteQuery(domain: String): Option[Query] = if (domain != null) Option(SiteQuery(domain)) else None

  protected def getTextQuery(queryText: String, quoted: Boolean): Option[Query] = {
    def copyFieldQuery(query:Query, field: String) = {
      query match {
        case null => null
        case query: TermQuery => copy(query, field)
        case query: PhraseQuery => copy(query, field)
        case _ => throw new QueryParserException(s"failed to copy query: ${query.toString}")
      }
    }

    def addSiteQuery(baseQuery: BooleanQuery, query: Query) {
      query match {
        case query: TermQuery =>
          val q = copyFieldQuery(query, if (Domain.isValid(query.getTerm.text)) "site" else "site_keywords")
          q.setBoost(siteBoost)
          baseQuery.add(q, SHOULD)
        case _ =>
      }
    }

    def saveStemmedTerms(query: Query, parent: Query) {
      stemmedTerms ++= getTermSeq("ts", query)
    }

    val booleanQuery = new BooleanQuery(true) with Coordinator // add Coordinator trait for TopLevelQuery

    super.getFieldQuery("t", queryText, quoted).foreach{ query =>
      booleanQuery.add(query, Occur.SHOULD)
      booleanQuery.add(copyFieldQuery(query, "c"), Occur.SHOULD)
      booleanQuery.add(copyFieldQuery(query, "title"), Occur.SHOULD)
      addSiteQuery(booleanQuery, query)
    }

    if(!quoted) {
      getStemmedFieldQuery("ts", queryText).foreach{ query =>
        saveStemmedTerms(query, booleanQuery)
        booleanQuery.add(query, Occur.SHOULD)
        booleanQuery.add(copyFieldQuery(query, "cs"), Occur.SHOULD)
        booleanQuery.add(copyFieldQuery(query, "title_stemmed"), Occur.SHOULD)
      }
    }
    if (booleanQuery.clauses().size == 0) None
    else Some(booleanQuery)
  }
}