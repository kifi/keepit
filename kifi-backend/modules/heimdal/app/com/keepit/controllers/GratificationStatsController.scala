package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.model.helprank.{ ReKeepRepo, KeepDiscoveryRepo }
import com.keepit.model.tracking.LibraryViewTrackingCommander
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.mvc.Action
import com.keepit.common.time._
import play.api.libs.json._
import com.keepit.commander.GratificationStatsCommander

class GratificationStatsController @Inject() (
    db: Database,
    libViewCmdr: LibraryViewTrackingCommander,
    gratStatsCommander: GratificationStatsCommander,
    reKeepRepo: ReKeepRepo,
    airbrake: AirbrakeNotifier) extends HeimdalServiceController with Logging {

  def getEligibleGratDatas = Action(parse.json) { request =>
    val userIds = request.body.as[Seq[Id[User]]]
    val gratDatas = userIds.map { gratStatsCommander.getGratData }.filter { _.isEligible }
    log.info(s"[GratData] Eligible Grat Datas collected. Sending: ${Json.toJson(gratDatas)}")
    Ok(Json.obj("gratDatas" -> gratDatas))
  }

  def getGratData(userId: Id[User]) = Action { request =>
    val gratData = gratStatsCommander.getGratData(userId)
    log.info(s"[GratData] Grat Data collected. Sending: ${Json.toJson(gratData)}")
    Ok(Json.obj("gratData" -> gratData))
  }

  def getGratDatas = Action(parse.json) { request =>
    val userIds = request.body.as[Seq[Id[User]]]
    val gratDatas = userIds.map { gratStatsCommander.getGratData }
    log.info(s"[GratData] Grat Datas collected. Sending: ${Json.toJson(gratDatas)}")
    Ok(Json.obj("gratDatas" -> gratDatas))
  }
}
