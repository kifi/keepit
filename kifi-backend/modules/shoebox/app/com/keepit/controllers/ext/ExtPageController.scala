package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.commanders._
import com.keepit.classify.{ Domain, DomainClassifier, DomainRepo }
import com.keepit.common.controller.{ ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator }
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.net.URI
import com.keepit.common.social._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.normalizer.NormalizationService
import com.keepit.search.SearchServiceClient
import com.keepit.social.BasicUser

import play.api.Play.current
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.Await
import scala.concurrent.duration._
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

}
