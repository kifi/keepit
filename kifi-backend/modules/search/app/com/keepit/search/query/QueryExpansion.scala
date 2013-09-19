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
import com.keepit.search.query.MediaQuery

trait QueryExpansion extends QueryParser {

  val siteBoost: Float

  def getStemmedPhrase(field: String, phraseStart: Int, phraseEnd: Int) = {
    stemmedTerms.slice(phraseStart, phraseEnd).foldLeft(new PhraseQuery()){ (phraseQuery, term) =>
      phraseQuery.add(new Term(field, term.text()))
      phraseQuery
    }
  }

  override def getFieldQuery(field: String, queryText: String, quoted: Boolean): Option[Query] = {
    field.toLowerCase match {
      case "site" => getSiteQuery(queryText)
      case "media" => Option(MediaQuery(queryText))
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

    def addSiteQuery(baseQuery: DisjunctionMaxQuery, queryText: String, query: Query) {
      (if (Domain.isValid(queryText)) Option(SiteQuery(queryText)) else None).orElse{
        query match {
          case query: TermQuery => Option(copyFieldQuery(query, "site_keywords"))
          case _ => None
        }
      }.foreach{ q =>
        q.setBoost(siteBoost)
        baseQuery.add(q)
      }
    }

    val disjunct = new DisjunctionMaxQuery(0.5f)

    super.getFieldQuery("t", queryText, quoted).foreach{ query =>
      disjunct.add(query)
      disjunct.add(copyFieldQuery(query, "c"))
      disjunct.add(copyFieldQuery(query, "title"))
      addSiteQuery(disjunct, queryText, query)
    }

    getStemmedFieldQuery("ts", queryText).foreach{ query =>
      if(!quoted) {
        disjunct.add(query)
        disjunct.add(copyFieldQuery(query, "cs"))
        disjunct.add(copyFieldQuery(query, "title_stemmed"))
      }
    }
    if (disjunct.iterator().hasNext) Some(disjunct) else None
  }
}
