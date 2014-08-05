package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.commander.HelpRankCommander
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal.SanitizedKifiHit
import com.keepit.model.{ Keep, User }

class HelpRankController @Inject() (
    airbrake: AirbrakeNotifier,
    helprankCommander: HelpRankCommander) extends HeimdalServiceController {

  implicit val fj = ExecutionContext.fj

  def kifiHit() = SafeAsyncAction(parse.tolerantJson) { request =>
    val json = request.body
    val clicker = (json \ "clickerId").as(Id.format[User])
    val kifiHit = (json \ "kifiHit").as[SanitizedKifiHit]
    helprankCommander.processKifiHit(clicker, kifiHit)
    Ok
  }

  def keepAttribution() = SafeAsyncAction(parse.tolerantJson) { request =>
    val json = request.body
    val userId = (json \ "userId").as(Id.format[User])
    val keeps = (json \ "keeps").as[Seq[Keep]]
    helprankCommander.keepAttribution(userId, keeps)
    Ok
  }

}
