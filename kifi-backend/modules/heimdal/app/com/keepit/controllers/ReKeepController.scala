package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.helprank.ReKeepRepo
import play.api.libs.json.Json
import play.api.mvc.Action

class ReKeepController @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    rekeepRepo: ReKeepRepo) extends HeimdalServiceController {

  def page(page: Int, size: Int) = Action { request =>
    val res = db.readOnlyMaster { implicit session =>
      rekeepRepo.page(page, size)
    }
    Ok(Json.toJson(res))
  }

}
