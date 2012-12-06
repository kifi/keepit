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
import com.keepit.common.controller.FortyTwoController

object HealthController extends FortyTwoController {

  def serviceView = AdminHtmlAction { implicit request =>
    val errorCount = inject[HealthcheckPlugin].errorCount
    Ok(views.html.serverInfo(currentService, currentVersion, compilationTime.toStandardTimeString, started.toStandardTimeString, errorCount))
  }

  def ping() = Action { implicit request =>
    Ok(inject[HealthcheckPlugin].errorCount.toString)
  }

  def isHealthy = Action { implicit request =>
    val error = inject[HealthcheckPlugin].errorCount

    error match {
      case 0 => Ok("Good!")
      case s: Int => Status(418)(error.toString)
    }
  }

  def causeError() = Action { implicit request =>
    0/0 // The realest fake error imaginable // Technically, this error is ∉ ℝ
    Ok("You cannot see this.")
  }

  def resetErrorCount() = AdminHtmlAction { implicit request =>
    inject[HealthcheckPlugin].resetErrorCount

    Redirect(routes.HealthController.serviceView)
  }
}
