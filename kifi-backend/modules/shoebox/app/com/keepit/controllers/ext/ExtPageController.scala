package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.net.URI
import play.api.libs.json._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{ Failure, Success }

class ExtPageController @Inject() (
  val userActionsHelper: UserActionsHelper,
  userIpAddressCommander: UserIpAddressCommander,
  pageCommander: PageCommander)
    extends UserActions with ShoeboxServiceController {

  def getPageInfo() = UserAction.async(parse.tolerantJson) { request =>
    userIpAddressCommander.logUserByRequest(request)
    (request.body \ "url").asOpt[String].map { url =>
      URI.parse(url) match {
        case Success(uri) =>
          pageCommander.getPageInfo(uri, request.userId, request.experiments).map { info =>
            Ok(Json.toJson(info))
          }
        case Failure(e) =>
          log.error(s"Error parsing url: $url", e)
          Future.successful(BadRequest(Json.obj("error" -> "error_parsing_url", "url" -> url)))
      }
    }.getOrElse {
      log.warn(s"No url given: ${request.body}")
      Future.successful(BadRequest(Json.obj("error" -> s"no_url_given")))
    }
  }

}
