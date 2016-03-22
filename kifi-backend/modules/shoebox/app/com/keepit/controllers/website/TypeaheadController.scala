package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.controller.{ UserActions, ShoeboxServiceController, UserActionsHelper }
import com.keepit.model.UserExperimentType
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.commanders.{ EmailContactResult, UserContactResult, TypeaheadCommander }

case class TypeaheadSearchRequest(query: String, limit: Int, pictureUrl: Boolean, inviteStatus: Boolean)

class TypeaheadController @Inject() (
    airbrake: AirbrakeNotifier,
    commander: TypeaheadCommander,
    val userActionsHelper: UserActionsHelper) extends UserActions with ShoeboxServiceController with Logging {

  def searchWithInviteStatus(query: Option[String], limit: Option[Int], pictureUrl: Boolean) = UserAction.async { request =>
    commander.searchWithInviteStatus(request.userId, query.getOrElse(""), limit, pictureUrl) map { res =>
      Ok(Json.toJson(res))
    }
  }

  def searchForContacts(query: Option[String], limit: Option[Int]) = UserAction.async { request =>
    commander.searchForContactResults(request.userId, query.getOrElse(""), limit, includeSelf = true) map { res =>
      val res1 = res.collect {
        case u: UserContactResult => Json.toJson(u)
        case e: EmailContactResult => Json.toJson(e)
      }
      Ok(Json.toJson(res1))
    }
  }

}
