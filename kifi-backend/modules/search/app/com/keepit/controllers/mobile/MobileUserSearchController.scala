package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActions, UserActionsHelper, SearchServiceController }
import com.keepit.common.logging.Logging
import com.keepit.search.UserSearchCommander
import com.keepit.search.index.user._
import play.api.libs.json.Json
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.json.JsArray

class MobileUserSearchController @Inject() (
    userSearchCommander: UserSearchCommander,
    shoeboxClient: ShoeboxServiceClient,
    val userActionsHelper: UserActionsHelper) extends UserActions with SearchServiceController with Logging {

  val EXCLUDED_EXPERIMENTS = Seq("fake")

  def pageV1(queryText: String, filter: Option[String], pageNum: Int, pageSize: Int) = UserAction { request =>
    val userId = request.userId
    val userExps = request.experiments.map { _.value }
    log.info(s"user search: userId = $userId, userExps = ${userExps.mkString(" ")}")
    val excludedExperiments = if (userExps.contains("admin")) Seq() else EXCLUDED_EXPERIMENTS
    val friendRequests = shoeboxClient.getFriendRequestsRecipientIdBySender(userId)

    val res = userSearchCommander.userTypeahead(Some(userId), queryText, pageNum, pageSize, context = None, filter = filter, excludedExperiments = excludedExperiments)

    val requestedUsers = Await.result(friendRequests, 5 seconds).toSet

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

  def searchV1(queryText: String, filter: Option[String], context: Option[String], maxHits: Int) = UserAction { request =>
    val userId = request.userId
    val res = userSearchCommander.searchUsers(Some(userId), queryText, maxHits, context = context, filter = filter, excludeSelf = true)
    Ok(Json.toJson(res))
  }

}
