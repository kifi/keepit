package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.commander.HelpRankCommander
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal.SanitizedKifiHit
import com.keepit.model.{ Keep, User }
import play.api.libs.json.Json
import play.api.mvc.Action
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class HelpRankController @Inject() (
    airbrake: AirbrakeNotifier,
    helprankCommander: HelpRankCommander) extends HeimdalServiceController {

  def processKifiHit() = Action.async(parse.tolerantJson) { request =>
    val json = request.body
    val clicker = (json \ "clickerId").as(Id.format[User])
    val kifiHit = (json \ "kifiHit").as[SanitizedKifiHit]
    helprankCommander.processKifiHit(clicker, kifiHit) map { r =>
      Ok
    }
  }

  def processKeepAttribution() = Action.async(parse.tolerantJson) { request =>
    val json = request.body
    val userId = (json \ "userId").as(Id.format[User])
    val keeps = (json \ "keeps").as[Seq[Keep]]
    helprankCommander.processKeepAttribution(userId, keeps) map { r =>
      Ok
    }
  }

  def getKeepAttributionInfo(userId: Id[User]) = Action { request =>
    Ok(Json.toJson(helprankCommander.getKeepAttributionInfo(userId)))
  }

}
