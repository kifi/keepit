package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.User
import com.keepit.model.helprank.UserBookmarkClicksRepo
import play.api.libs.json.Json
import play.api.mvc.Action

class UserKeepInfoController @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    userKeepInfoRepo: UserBookmarkClicksRepo) extends HeimdalServiceController {

  def getClickCounts(userId: Id[User]) = Action { request =>
    val (uniqueKeepsClicked, totalClicks) = db.readOnlyMaster { implicit session =>
      userKeepInfoRepo.getClickCounts(userId)
    }
    Ok(Json.obj(
      "uniqueKeepsClicked" -> uniqueKeepsClicked,
      "totalClicks" -> totalClicks
    ))
  }

  def getReKeepCounts(userId: Id[User]) = Action { request =>
    val (rekeepCount, rekeepTotalCount) = db.readOnlyMaster { implicit session =>
      userKeepInfoRepo.getReKeepCounts(userId)
    }
    Ok(Json.obj(
      "rekeepCount" -> rekeepCount,
      "rekeepTotalCount" -> rekeepTotalCount
    ))
  }

}
