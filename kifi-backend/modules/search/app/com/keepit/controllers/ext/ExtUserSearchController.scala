package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{ActionAuthenticator, BrowserExtensionController, SearchServiceController}
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.User
import com.keepit.search.MainSearcherFactory
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.user._
import play.api.libs.json.Json
import com.keepit.shoebox.ShoeboxServiceClient

class ExtUserSearchController @Inject()(
  searcherFactory: MainSearcherFactory,
  filterFactory: UserSearchFilterFactory,
  shoeboxClient: ShoeboxServiceClient,
  actionAuthenticator: ActionAuthenticator
) extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging {

  private def createFilter(userId: Option[Id[User]], filter: Option[String], context: Option[String]) = {
    filter match {
      case Some("f") if userId.isDefined => filterFactory.friendsOnly(userId.get, context)
      case Some("non-f") if userId.isDefined => filterFactory.nonFriendsOnly(userId.get, context)
      case _ => filterFactory.default(context)
    }
  }

  def page(queryText: String, filter: Option[String], pageNum: Int, pageSize: Int) = AuthenticatedJsonAction { request =>
    val userId = request.userId
    val searchFilter = createFilter(Some(userId), filter, None)
    val searcher = searcherFactory.getUserSearcher
    val parser = new UserQueryParser(DefaultAnalyzer.defaultAnalyzer)
    val res = parser.parse(queryText) match {
      case None => UserSearchResult(Array.empty[UserHit], context = "")
      case Some(q) => searcher.searchPagingWithFilter(q, searchFilter, pageNum, pageSize)
    }
    Ok(Json.toJson(res))
  }

  def search(queryText: String, filter: Option[String], context: Option[String], maxHits: Int) = AuthenticatedJsonAction { request =>
    val userId = request.userId
    val searchFilter = createFilter(Some(userId), filter, context)
    val searcher = searcherFactory.getUserSearcher
    val parser = new UserQueryParser(DefaultAnalyzer.defaultAnalyzer)

    val res = parser.parse(queryText) match {
      case None => UserSearchResult(Array.empty[UserHit], context.getOrElse(""))
      case Some(q) => searcher.searchWithFilter(q, maxHits, searchFilter)
    }

    Ok(Json.toJson(res))
  }

}
