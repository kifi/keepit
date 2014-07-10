package com.keepit.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{ ActionAuthenticator, BrowserExtensionController, SearchServiceController }
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
import com.keepit.typeahead.PrefixFilter

class ExtUserSearchController @Inject() (
    searcherFactory: MainSearcherFactory,
    filterFactory: UserSearchFilterFactory,
    shoeboxClient: ShoeboxServiceClient,
    actionAuthenticator: ActionAuthenticator) extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging {

  val EXCLUDED_EXPERIMENTS = Seq("fake")

  private def createFilter(userId: Option[Id[User]], filter: Option[String], context: Option[String]) = {
    filter match {
      case Some("f") => filterFactory.friendsOnly(userId.get, context)
      case Some("non-f") => filterFactory.nonFriendsOnly(userId.get, context)
      case _ => filterFactory.default(userId, context, excludeSelf = true) // may change this later
    }
  }

  def page(queryText: String, filter: Option[String], pageNum: Int, pageSize: Int) = JsonAction.authenticated { request =>
    val userId = request.userId
    val userExps = request.experiments.map { _.value }
    log.info(s"user search: userId = ${userId}, userExps = ${userExps.mkString(" ")}")
    val excludedExperiments = if (userExps.contains("admin")) Seq() else EXCLUDED_EXPERIMENTS
    val friendRequests = shoeboxClient.getFriendRequestsBySender(userId)
    val searchFilter = createFilter(Some(userId), filter, None)
    val searcher = searcherFactory.getUserSearcher
    val parser = new UserQueryParser(DefaultAnalyzer.defaultAnalyzer)
    val queryTerms = PrefixFilter.normalize(queryText).split("\\s+")
    val res = parser.parseWithUserExperimentConstrains(queryText, excludedExperiments) match {
      case None => UserSearchResult(Array.empty[UserHit], context = "")
      case Some(q) => searcher.searchPaging(q, searchFilter, pageNum, pageSize, queryTerms)
    }

    val requestedUsers = Await.result(friendRequests, 5 seconds).filter(_.state == FriendRequestStates.ACTIVE).map { _.recipientId }.toSet

    val jsVals = res.hits.map {
      case UserHit(id, basicUser, isFriend) =>
        val status = {
          if (isFriend) "friend"
          else if (requestedUsers.contains(id)) "requested"
          else ""
        }

        Json.obj("user" -> Json.toJson(basicUser), "status" -> status)
    }

    Ok(JsArray(jsVals))
  }

  def search(queryText: String, filter: Option[String], context: Option[String], maxHits: Int) = JsonAction.authenticated { request =>
    val userId = request.userId
    val searchFilter = createFilter(Some(userId), filter, context)
    val searcher = searcherFactory.getUserSearcher
    val parser = new UserQueryParser(DefaultAnalyzer.defaultAnalyzer)
    val queryTerms = PrefixFilter.normalize(queryText).split("\\s+")
    val res = parser.parse(queryText) match {
      case None => UserSearchResult(Array.empty[UserHit], context.getOrElse(""))
      case Some(q) => searcher.search(q, maxHits, searchFilter, queryTerms)
    }

    Ok(Json.toJson(res))
  }

}
