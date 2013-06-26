package com.keepit.search.comment

import com.keepit.classify.Domain
import com.keepit.search.query.parser.QueryParser
import com.keepit.search.query.parser.DefaultSyntax
import com.keepit.search.query.parser.PercentMatch
import com.keepit.search.query.parser.QueryExpansion
import com.keepit.search.query.parser.QueryParserException
import com.keepit.search.query.Coordinator
import com.keepit.search.query.ProximityQuery
import com.keepit.search.query.QueryUtil._
import com.keepit.search.query.SemanticVectorQuery
import com.keepit.search.query.SiteQuery
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanClause.Occur._
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.DisjunctionMaxQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import scala.collection.mutable.ArrayBuffer
import scala.concurrent._
import ExecutionContext.Implicits.global

class CommentQueryParser(
  analyzer: Analyzer,
  stemmingAnalyzer: Analyzer
) extends QueryParser(analyzer, stemmingAnalyzer) with DefaultSyntax with PercentMatch {

  import CommentFields._

  var enableCoord = false

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
          val q = copyFieldQuery(query, if (Domain.isValid(query.getTerm.text)) siteField else siteKeywordField)
          baseQuery.add(q)
        case _ =>
      }
    }

    val disjunct = new DisjunctionMaxQuery(0.5f)

    super.getFieldQuery(textField, queryText, quoted).foreach{ query =>
      disjunct.add(query)
      disjunct.add(copyFieldQuery(query, titleField))
      disjunct.add(copyFieldQuery(query, textField))
      disjunct.add(copyFieldQuery(query, participantNameField))
      addSiteQuery(disjunct, query)
    }

    getStemmedFieldQuery(textStemmedField, queryText).foreach{ query =>
      if(!quoted) {
        disjunct.add(query)
        disjunct.add(copyFieldQuery(query, titleStemmedField))
      }
    }
    if (disjunct.iterator().hasNext) Some(disjunct) else None
  }
}
