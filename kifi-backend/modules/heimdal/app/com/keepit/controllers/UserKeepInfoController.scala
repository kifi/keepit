package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.model.helprank.UserBookmarkClicksRepo
import play.api.libs.json.Json
import play.api.mvc.Action

class UserKeepInfoController @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    userKeepInfoRepo: UserBookmarkClicksRepo) extends HeimdalServiceController with Logging {

  def getReKeepCountsByUserUri(userId: Id[User], uriId: Id[NormalizedURI]) = Action { request =>
    val res = db.readOnlyMaster { implicit ro =>
      userKeepInfoRepo.getByUserUri(userId, uriId).map { r =>
        (r.rekeepCount, r.rekeepTotalCount)
      } getOrElse (0, 0)
    }
    Ok(Json.obj("rekeepCount" -> res._1, "rekeepTotalCount" -> res._2))
  }

}
