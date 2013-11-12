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
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.model.FriendRequestStates
import play.api.libs.json.JsArray

class ExtUserSearchController @Inject()(
  searcherFactory: MainSearcherFactory,
  filterFactory: UserSearchFilterFactory,
  shoeboxClient: ShoeboxServiceClient,
  actionAuthenticator: ActionAuthenticator
) extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging {

  private def createFilter(userId: Option[Id[User]], filter: Option[String], context: Option[String]) = {
    filter match {
      case Some("f") => filterFactory.friendsOnly(userId.get, context)
      case Some("non-f") => filterFactory.nonFriendsOnly(userId.get, context)
      case _ => filterFactory.default(userId, context)
    }
  }

  def page(queryText: String, filter: Option[String], pageNum: Int, pageSize: Int) = AuthenticatedJsonAction { request =>
    val userId = request.userId
    val friendRequests = shoeboxClient.getFriendRequestsBySender(userId)
    val searchFilter = createFilter(Some(userId), filter, None)
    val searcher = searcherFactory.getUserSearcher
    val parser = new UserQueryParser(DefaultAnalyzer.defaultAnalyzer)
    val res = parser.parse(queryText) match {
      case None => UserSearchResult(Array.empty[UserHit], context = "")
      case Some(q) => searcher.searchPagingWithFilter(q, searchFilter, pageNum, pageSize)
    }

    val requestedUsers = Await.result(friendRequests, 5 seconds).filter(_.state == FriendRequestStates.ACTIVE).map{_.recipientId}.toSet

    val jsVals = res.hits.map{ case UserHit(id, basicUser, isFriend) =>
      val connectionStatus = {
        if (isFriend) "friend"
        else if (requestedUsers.contains(id)) "requested"
        else ""
      }

      Json.obj("basicUser" -> Json.toJson(basicUser), "connectionStatus" -> connectionStatus)
    }

    Ok(JsArray(jsVals))
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
