package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.User
import com.keepit.model.helprank.KeepDiscoveryRepo
import play.api.libs.json.Json
import play.api.mvc.Action

class KeepDiscoveryController @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    keepDiscoveryRepo: KeepDiscoveryRepo) extends HeimdalServiceController {

  def page(page: Int, size: Int) = Action { request =>
    val res = db.readOnlyMaster { implicit session =>
      keepDiscoveryRepo.page(page, size)
    }
    Ok(Json.toJson(res))
  }

  def getDiscoveryCountByKeeper(userId: Id[User]) = Action { request =>
    val res = db.readOnlyMaster { implicit session =>
      keepDiscoveryRepo.getDiscoveryCountByKeeper(userId)
    }
    Ok(Json.toJson(res))
  }
}
