package com.keepit.controllers.mobile

import com.keepit.classify.{Domain, DomainRepo, DomainStates}
import com.keepit.common.controller.{ShoeboxServiceController, MobileController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.commanders.{UserCommander, BasicSocialUser}

import play.api.Play.current
import play.api.libs.json.{JsObject, Json}

import com.google.inject.Inject
import com.keepit.common.net.URI
import com.keepit.controllers.core.NetworkInfoLoader
import com.keepit.common.social.BasicUserRepo
import com.keepit.social.BasicUser
import com.keepit.common.analytics.{Event, EventFamilies, Events}
import play.api.libs.concurrent.Akka

class MobileUserController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  userCommander: UserCommander)
    extends MobileController(actionAuthenticator) with ShoeboxServiceController {

  def getFriends() = AuthenticatedJsonAction { request =>
    Ok(Json.toJson(userCommander.getFriends(request.user, request.experiments)))
  }

  def socialNetworkInfo() = AuthenticatedJsonAction { request =>
    Ok(Json.toJson(userCommander.socialNetworkInfo(request.userId)))
  }
}
