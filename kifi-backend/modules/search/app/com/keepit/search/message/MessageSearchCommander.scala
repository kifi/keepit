package com.keepit.search.message

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.{Lang, LangDetector}
import com.keepit.search.index.DefaultAnalyzer

import play.api.libs.json.JsValue

import com.google.inject.Inject


class MessageSearchCommander @Inject() (indexer: MessageIndexer){

  val resultPageSize = 10

  def search(userId: Id[User], query: String, page: Int) : Seq[JsValue] = {
    val lang = LangDetector.detect(query, Lang("en"))

    val searcher = new MessageSearcher(indexer.getSearcher)

    val parser = new MessageQueryParser(
      DefaultAnalyzer.getAnalyzer(lang),
      DefaultAnalyzer.getAnalyzerWithStemmer(lang)
    )

    parser.parse(query).map{ parsedQuery =>
      searcher.search(userId, parsedQuery, page*resultPageSize, resultPageSize)
    }.getOrElse(Seq())
  }

}
