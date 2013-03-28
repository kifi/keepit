
package com.keepit.controllers.search

import com.google.inject.Inject
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.db.Id
import com.keepit.model.{SocialConnectionRepo, NormalizedURI, BookmarkRepo, User}
import com.keepit.search.{SearchFilter, SearchConfigManager, Lang, MainSearcherFactory}
import play.api.libs.json.{JsNumber, JsArray}
import play.api.mvc.Action
import com.keepit.common.db.slick.Database
import views.html

class SearchController @Inject()(
    db: Database,
    socialConnectionRepo: SocialConnectionRepo,
    searchConfigManager: SearchConfigManager,
    bookmarkRepo: BookmarkRepo,
    searcherFactory: MainSearcherFactory
  ) extends FortyTwoController {

  def searchKeeps(userId: Id[User], query: String) = Action { request =>
    val searcher = searcherFactory.bookmarkSearcher(userId)
    val uris = searcher.search(query, Lang("en"))
    Ok(JsArray(uris.toSeq.map(JsNumber(_))))
  }

  def explain(query: String, userId: Id[User], uriId: Id[NormalizedURI]) = Action { request =>
    val friendIds = db.readOnly { implicit s =>
      socialConnectionRepo.getFortyTwoUserConnections(userId)
    }
    val (config, _) = searchConfigManager.getConfig(userId, query)

    val searcher = searcherFactory(userId, friendIds, SearchFilter.default(), config)
    val explanation = searcher.explain(query, uriId)

    Ok(html.admin.explainResult(query, userId, uriId, explanation))
  }
}
