package com.keepit.controllers.mobile

import com.keepit.commanders._
import com.keepit.commanders.KeepInfosWithCollection._

import com.keepit.commanders._
import com.keepit.heimdal._
import com.keepit.common.controller.{ShoeboxServiceController, MobileController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.commanders.{UserCommander, BasicSocialUser}

import play.api.Play.current
import play.api.libs.json.{JsObject, Json, JsValue}

import com.google.inject.Inject
import com.keepit.common.net.URI
import com.keepit.controllers.core.NetworkInfoLoader
import com.keepit.social.BasicUser
import com.keepit.common.analytics.{Event, EventFamilies, Events}
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class MobileBookmarksController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  bookmarksCommander: BookmarksCommander,
  userEventContextBuilder: EventContextBuilderFactory)
    extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  def keepMultiple() = AuthenticatedJsonAction { request =>
    request.body.asJson.flatMap(Json.fromJson[KeepInfosWithCollection](_).asOpt) map { fromJson =>
      val contextBuilder = userEventContextBuilder(Some(request))
      val source = "MOBILE"
      contextBuilder += ("source", source)
      val (keeps, addedToCollection) = bookmarksCommander.keepMultiple(fromJson, request.user, request.experiments, contextBuilder, source)
      Ok(Json.obj(
        "keeps" -> keeps,
        "addedToCollection" -> addedToCollection
      ))
    } getOrElse {
      log.error(s"can't parse object from request ${request.body} for user ${request.user}")
      BadRequest(Json.obj("error" -> "Could not parse object from request body"))
    }
  }

}
