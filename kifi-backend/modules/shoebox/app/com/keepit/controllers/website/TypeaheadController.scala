package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.typeahead.socialusers.SocialUserTypeahead
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, WebsiteController }
import com.keepit.common.db.Id
import com.keepit.model._
import scala.concurrent.{ Await, Future }
import com.keepit.typeahead.{ PrefixFilter, TypeaheadHit }
import com.keepit.common.db.slick.DBSession.RSession
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.abook.ABookServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration.Duration
import com.keepit.social.{ SocialNetworks, SocialNetworkType }
import scala.collection.mutable
import com.keepit.commanders.TypeaheadCommander

case class TypeaheadSearchRequest(query: String, limit: Int, pictureUrl: Boolean, inviteStatus: Boolean)

class TypeaheadController @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    commander: TypeaheadCommander,
    actionAuthenticator: ActionAuthenticator) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController with Logging {

  def searchWithInviteStatus(query: Option[String], limit: Option[Int], pictureUrl: Boolean, dedupEmail: Boolean) = JsonAction.authenticatedAsync { request =>
    commander.searchWithInviteStatus(request.userId, query.getOrElse(""), limit, pictureUrl, dedupEmail) map { res =>
      Ok(Json.toJson(res))
    }
  }

}
