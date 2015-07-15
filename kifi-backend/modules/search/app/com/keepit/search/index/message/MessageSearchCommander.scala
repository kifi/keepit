package com.keepit.search.index.message

import com.keepit.common.db.Id
import com.keepit.model.{ ExperimentType, User }
import com.keepit.search.{ SearchConfigManager, Lang, LangDetector }
import com.keepit.search.index.DefaultAnalyzer

import play.api.libs.json.JsValue

import com.google.inject.Inject
import com.keepit.common.time.Clock
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class MessageSearchCommander @Inject() (indexer: MessageIndexer, searchConfigManager: SearchConfigManager, clock: Clock) {

  val resultPageSize = 10

  def search(userId: Id[User], query: String, page: Int, experiments: Set[ExperimentType]): Future[Seq[JsValue]] = {
    val lang = LangDetector.detect(query, Lang("en"))

    searchConfigManager.getConfigFuture(userId, experiments).map {
      case (config, _) =>
        val searcher = new MessageSearcher(indexer.getSearcher, config, clock)

        val parser = new MessageQueryParser(
          DefaultAnalyzer.getAnalyzer(lang),
          DefaultAnalyzer.getAnalyzerWithStemmer(lang)
        )

        parser.parse(query).map { parsedQuery =>
          searcher.search(userId, parsedQuery, page * resultPageSize, resultPageSize)
        }.getOrElse(Seq())
    }
  }

}
