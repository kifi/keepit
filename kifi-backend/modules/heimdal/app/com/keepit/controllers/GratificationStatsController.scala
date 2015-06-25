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

  def getEligibleGratData = Action { request =>
    val userIds = request.getQueryString("userIds").asInstanceOf[Seq[Id[User]]]
    val gratDatas = userIds.map { gratStatsCommander.getGratData }.filter { _.isEligible }
    Ok(Json.arr(gratDatas.map { gratData => Json.toJson(gratData) }))
  }

  def getGratData(userId: Id[User]) = Action { request =>
    val gratData = gratStatsCommander.getGratData(userId)
    Ok(Json.toJson(gratData))
  }

  def getGratDatas = Action { request =>
    val userIds = request.getQueryString("userIds").asInstanceOf[Seq[Id[User]]]
    val gratDatas = userIds.map { gratStatsCommander.getGratData }
    Ok(Json.arr(gratDatas.map { gratData => Json.toJson(gratData) }))
  }
}
