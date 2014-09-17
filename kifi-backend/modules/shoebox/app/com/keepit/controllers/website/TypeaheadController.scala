package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.controller._
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.commanders.{ EmailContactResult, UserContactResult, TypeaheadCommander }

case class TypeaheadSearchRequest(query: String, limit: Int, pictureUrl: Boolean, inviteStatus: Boolean)

class TypeaheadController @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    commander: TypeaheadCommander,
    userActionsHelper: UserActionsHelper) extends WebController(userActionsHelper) with ShoeboxServiceController with Logging {

  def searchWithInviteStatus(query: Option[String], limit: Option[Int], pictureUrl: Boolean, dedupEmail: Boolean) = UserAction.async { request =>
    commander.searchWithInviteStatus(request.userId, query.getOrElse(""), limit, pictureUrl, dedupEmail) map { res =>
      Ok(Json.toJson(res))
    }
  }

  def searchForContacts(query: Option[String], limit: Option[Int]) = UserAction.async { request =>
    commander.searchForContacts(request.userId, query.getOrElse(""), limit) map { res =>
      val res1 = res.map { r =>
        r match {
          case u: UserContactResult => Json.toJson(u)
          case e: EmailContactResult => Json.toJson(e)
        }
      }
      Ok(Json.toJson(res1))
    }
  }

}
