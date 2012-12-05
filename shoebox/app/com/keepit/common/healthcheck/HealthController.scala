package com.keepit.common.healthcheck

import com.keepit.common.controller.FortyTwoServices.compilationTime
import com.keepit.common.controller.FortyTwoServices.currentService
import com.keepit.common.controller.FortyTwoServices.currentVersion
import com.keepit.common.controller.FortyTwoServices.started
import com.keepit.common.logging.Logging
import com.keepit.common.time.dateTimeToRichDateTime
import com.keepit.inject._

import play.api.Play.current
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial

object HealthController extends Controller with Logging with SecureSocial {

  def serviceView = SecuredAction(false) { implicit request =>
    Ok(views.html.serverInfo(currentService, currentVersion, compilationTime.toStandardTimeString, started.toStandardTimeString))
  }

  def ping() = Action { implicit request =>

    Ok(inject[HealthcheckPlugin].errorCount.toString)
  }

  def fakeError = Action { implicit request =>
    0/0 // The realest fake error imaginable
    Ok("")
  }
}
