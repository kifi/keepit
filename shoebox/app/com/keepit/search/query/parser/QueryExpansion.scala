package com.keepit.search.query.parser

import com.keepit.classify.Domain
import com.keepit.search.query.Coordinator
import com.keepit.search.query.QueryUtil._
import com.keepit.search.query.SiteQuery
import org.apache.lucene.index.Term
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.DisjunctionMaxQuery
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

    def addSiteQuery(baseQuery: DisjunctionMaxQuery, query: Query) {
      query match {
        case query: TermQuery =>
          val q = copyFieldQuery(query, if (Domain.isValid(query.getTerm.text)) "site" else "site_keywords")
          q.setBoost(siteBoost)
          baseQuery.add(q)
        case _ =>
      }
    }

    def saveStemmedTerms(query: Query, parent: Query) {
      stemmedTerms ++= getTermSeq("ts", query)
    }

    val disjunct = new DisjunctionMaxQuery(0.5f)

    super.getFieldQuery("t", queryText, quoted).foreach{ query =>
      disjunct.add(query)
      disjunct.add(copyFieldQuery(query, "c"))
      disjunct.add(copyFieldQuery(query, "title"))
      addSiteQuery(disjunct, query)
    }

    if(!quoted) {
      getStemmedFieldQuery("ts", queryText).foreach{ query =>
        saveStemmedTerms(query, disjunct)
        disjunct.add(query)
        disjunct.add(copyFieldQuery(query, "cs"))
        disjunct.add(copyFieldQuery(query, "title_stemmed"))
      }
    }
    if (disjunct.iterator().hasNext) Some(disjunct) else None
  }
}
