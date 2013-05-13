package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.search.graph.URIGraphSearcher
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.Searcher
import com.keepit.search.query.SiteQuery
import com.keepit.search.query.parser.DefaultSyntax
import com.keepit.search.query.parser.QueryParser
import com.keepit.search.query.parser.PercentMatch
import com.keepit.search.query.parser.QueryExpansion
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

  val myUriEdges = uriGraphSearcher.myUriEdgeSet
  val myUris = myUriEdges.destIdLongSet

  def getSearcher(query: Query) = {
    val indexReader = uriGraphSearcher.openPersonalIndex(query) match {
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
    parser.parse(queryString).map{ query =>
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
    new BookmarkQueryParser(DefaultAnalyzer.forParsing(lang), DefaultAnalyzer.forParsingWithStemmer(lang))
  }
}

class BookmarkQueryParser(defaultAnalyzer: Analyzer, stemmingAnalyzer: Analyzer)
extends QueryParser(defaultAnalyzer, stemmingAnalyzer) with DefaultSyntax with PercentMatch with QueryExpansion {
  override val siteBoost = 1.0f
}
