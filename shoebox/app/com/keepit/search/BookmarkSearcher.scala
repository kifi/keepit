package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.search.graph.URIGraphSearcher
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.QueryParser
import com.keepit.search.index.Searcher
import com.keepit.model._
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.joda.time.DateTime
import scala.math._


class BookmarkSearcher(userId: Id[User], articleSearcher: Searcher, uriGraphSearcher: URIGraphSearcher) extends Logging {
  val currentTime = currentDateTime.getMillis()

  val myUriEdges = uriGraphSearcher.getUserToUriEdgeSetWithCreatedAt(userId, publicOnly = false)
  val myUris = myUriEdges.destIdLongSet

  def getSearcher(query: Query) = {
    val indexReader = uriGraphSearcher.openPersonalIndex(userId, query) match {
      case Some((personalReader, personalIdMapper)) =>
        articleSearcher.indexReader.add(personalReader, personalIdMapper)
      case None =>
        articleSearcher.indexReader
    }
    new Searcher(indexReader)
  }

  def search(queryString: String, lang: Lang) = {
    var result = Set.empty[Long]
    val parser = BookmarkQueryParser(lang)
    parser.setPercentMatch(0.0f)
    parser.enableCoord = true
    parser.parseQuery(queryString).map{ query =>
      getSearcher(query).doSearch(query){ (scorer, mapper) =>
        var doc = scorer.nextDoc()
        while (doc != NO_MORE_DOCS) {
          val id = mapper.getId(doc)
          if (myUris.contains(id)) result += id
          doc = scorer.nextDoc()
        }
      }
    }
    result
  }
}

object BookmarkQueryParser {

  def apply(lang: Lang): BookmarkQueryParser = {
    val parser = new BookmarkQueryParser(DefaultAnalyzer.forParsing(lang))
    DefaultAnalyzer.forParsingWithStemmer(lang).foreach{ parser.setStemmingAnalyzer(_) }
    parser
  }
}

class BookmarkQueryParser(analyzer: Analyzer) extends QueryParser(analyzer) {

  super.setAutoGeneratePhraseQueries(true)

  var enableCoord = false

  override def getFieldQuery(field: String, queryText: String, quoted: Boolean) = {
    field.toLowerCase match {
      case "site" => getSiteQuery(queryText)
      case _ => getTextQuery(queryText, quoted)
    }
  }

  private def getTextQuery(queryText: String, quoted: Boolean) = {
    val booleanQuery = new BooleanQuery(true)

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
}
