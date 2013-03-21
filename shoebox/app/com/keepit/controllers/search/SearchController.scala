
package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db.Id
import com.keepit.model.{BookmarkRepo, User}
import com.keepit.search.{Lang, MainSearcherFactory}
import play.api.libs.json.{JsNumber, JsArray}
import play.api.mvc.Action

class SearchController @Inject()(
    bookmarkRepo: BookmarkRepo,
    searcherFactory: MainSearcherFactory
  ) extends FortyTwoController {
  def searchKeeps(userId: Id[User], query: String) = Action { request =>
    val searcher = searcherFactory.bookmarkSearcher(userId)
    val uris = searcher.search(query, Lang("en"))
    Ok(JsArray(uris.toSeq.map(JsNumber(_))))
  }
}
