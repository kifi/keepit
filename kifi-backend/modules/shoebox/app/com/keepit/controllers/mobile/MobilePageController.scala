package com.keepit.controllers.mobile

import com.google.inject.Inject

import com.keepit.commanders._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller._
import com.keepit.common.db.slick._
import com.keepit.common.net.URI
import com.keepit.model._
import play.api.libs.json.Json._
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.{ Failure, Success }

class MobilePageController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  keepRepo: KeepRepo,
  userCommander: UserCommander,
  userConnectionsCommander: UserConnectionsCommander,
  collectionCommander: CollectionCommander,
  pageCommander: PageCommander)
    extends UserActions with ShoeboxServiceController {

  def getPageDetails() = UserAction(parse.tolerantJson) { request =>
    val urlOpt = (request.body \ "url").asOpt[String]
    urlOpt match {
      case Some(url) =>
        URI.parse(url) match {
          case Success(_) =>
            val info = pageCommander.getPageDetails(url, request.userId, request.experiments)
            Ok(Json.toJson(info))
          case Failure(e) =>
            log.error(s"Error parsing url: $url", e)
            BadRequest(Json.obj("error" -> s"Error parsing url: $url"))
        }
      case None =>
        // Change this back to non-optional when clients are on latest Android (2015/2/17)
        log.warn(s"[getPageDetails] No URL provided for request $request")
        BadRequest(Json.obj("error" -> "no_url_provided"))
    }
  }

}
