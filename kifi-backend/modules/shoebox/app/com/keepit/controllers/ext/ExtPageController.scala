package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator }
import com.keepit.common.net.URI
import play.api.libs.json._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{ Failure, Success }

class ExtPageController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  pageCommander: PageCommander)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def getPageDetails() = JsonAction.authenticatedParseJson { request =>
    val url = (request.body \ "url").as[String]
    if (url.isEmpty) throw new Exception(s"empty url for json ${request.body} for user ${request.user}")
    URI.parse(url) match {
      case Success(_) =>
        val info = pageCommander.getPageDetails(url, request.userId, request.experiments)
        Ok(Json.toJson(info))
      case Failure(e) =>
        log.error(s"Error parsing url: $url", e)
        BadRequest(Json.obj("error" -> s"Error parsing url: $url"))
    }
  }

  def getPageInfo() = JsonAction.authenticatedParseJsonAsync { request =>
    val url = (request.body \ "url").as[String]
    URI.parse(url) match {
      case Success(uri) =>
        pageCommander.getPageInfo(uri, request.userId, request.experiments).map { info =>
          Ok(Json.toJson(info))
        }
      case Failure(e) =>
        log.error(s"Error parsing url: $url", e)
        Future.successful(BadRequest(Json.obj("error" -> s"Error parsing url: $url")))
    }
  }

}
