package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.User
import com.keepit.model.tracking.LibraryViewTrackingCommander
import play.api.mvc.Action
import com.keepit.common.time._
import play.api.libs.json._

class StatsController @Inject() (libViewCmdr: LibraryViewTrackingCommander) extends HeimdalServiceController with Logging {

  def getOwnerLibraryViewStats(userId: Id[User]) = Action { request =>
    val LIBRARY_LIMIT = 7 // max libraries we want to show the user

    val since = currentDateTime.minusWeeks(1)
    val cnt = libViewCmdr.getTotalViews(userId, since)
    val map = libViewCmdr.getTopViewedLibrariesAndCounts(userId, since, LIBRARY_LIMIT)
    Ok(Json.obj("cnt" -> cnt, "map" -> map))
  }
}
