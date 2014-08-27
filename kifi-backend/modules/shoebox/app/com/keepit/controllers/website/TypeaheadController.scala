package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, WebsiteController }
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
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

  def searchForContacts(query: Option[String], limit: Option[Int]) = JsonAction.authenticatedAsync { request =>
    commander.searchForContacts(request.userId, query.getOrElse(""), limit) map { res =>
      Ok(Json.toJson(res))
    }
  }

}
