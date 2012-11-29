package com.keepit.common.healthcheck

import play.api.data._
import play.api._
import play.api.Play.current
import play.api.data.Forms._
import play.api.data.validation.Constraints._
import play.api.libs.ws.WS
import play.api.mvc._
import securesocial.core._
import com.keepit.common.controller.FortyTwoServices._
import com.keepit.common.logging.Logging
import com.keepit.common.time._

object HealthController extends Controller with Logging with SecureSocial {

  def serviceView = SecuredAction(false) { implicit request =>
    Ok(views.html.serverInfo(currentService, currentVersion, compilationTime.toStandardTimeString, started.toStandardTimeString))
  }
}
