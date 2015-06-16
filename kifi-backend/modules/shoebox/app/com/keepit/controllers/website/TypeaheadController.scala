package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.controller.{ UserActions, ShoeboxServiceController, UserActionsHelper }
import com.keepit.model.ExperimentType
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.commanders.{ AliasContactResult, EmailContactResult, UserContactResult, TypeaheadCommander }

case class TypeaheadSearchRequest(query: String, limit: Int, pictureUrl: Boolean, inviteStatus: Boolean)

class TypeaheadController @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    commander: TypeaheadCommander,
    val userActionsHelper: UserActionsHelper) extends UserActions with ShoeboxServiceController with Logging {

  def searchWithInviteStatus(query: Option[String], limit: Option[Int], pictureUrl: Boolean, dedupEmail: Boolean) = UserAction.async { request =>
    commander.searchWithInviteStatus(request.userId, query.getOrElse(""), limit, pictureUrl, dedupEmail) map { res =>
      Ok(Json.toJson(res))
    }
  }

  def searchForContacts(query: Option[String], limit: Option[Int]) = UserAction.async { request =>
    commander.searchForContacts(request.userId, query.getOrElse(""), limit) map { res =>
      val includeAliases = request.experiments.contains(ExperimentType.ADMIN)
      val res1 = res.collect {
        case u: UserContactResult => Json.toJson(u)
        case e: EmailContactResult => Json.toJson(e)
        case a: AliasContactResult if includeAliases => Json.toJson(a)
      }
      Ok(Json.toJson(res1))
    }
  }

}
