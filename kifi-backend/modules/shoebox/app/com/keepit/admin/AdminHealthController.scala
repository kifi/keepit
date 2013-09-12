package com.keepit.controllers.admin

import com.keepit.common.time.RichDateTime
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.healthcheck._

import scala.util.Random

import play.api.Play.current
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial

import views.html

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.google.inject.Inject

class AdminHealthController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  healthcheckPlugin: HealthcheckPlugin,
  services: FortyTwoServices,
  airbrake: AirbrakeNotifier)
    extends AdminController(actionAuthenticator) {

  def serviceView = AdminHtmlAction { implicit request =>
    val errorCount = healthcheckPlugin.errorCount
    val recentErrors = healthcheckPlugin.errors()
    val cacheStats = CacheStatistics.getStatistics
    val (totalHits, totalMisses, totalSets) = (cacheStats.map(_._2).sum, cacheStats.map(_._3).sum, cacheStats.map(_._4).sum)
    Ok(html.admin.serverInfo(services.currentService, services.currentVersion, services.compilationTime.toStandardTimeString,
        services.started.toStandardTimeString, errorCount, recentErrors, cacheStats, totalHits, totalMisses, totalSets))
  }

  def getErrors() = AdminHtmlAction { implicit request =>
    Ok(healthcheckPlugin.errors().mkString("\n"))
  }

  def reportErrors() = AdminHtmlAction { implicit request =>
    healthcheckPlugin.reportErrors()
    Ok("reported")
  }

  def resetErrorCount() = AdminHtmlAction { implicit request =>
    healthcheckPlugin.resetErrorCount
    Redirect(routes.AdminHealthController.serviceView)
  }

  def causeError() = Action { implicit request =>
    throwException()
    Ok("You cannot see this :-P ")
  }

  private def throwException(): Unit = {
    if (Random.nextBoolean) {
      // throwing a X/0 exception. its a fixed stack exception with random message text
      (Random.nextInt) / 0
    }
    // throwing an array out bound exception. its a fixed stack exception with random message text
    (new Array[Int](1))(Random.nextInt + 1) = 1
  }

  def causeHandbrakeError(param: String) = Action { implicit request =>
    try {
      throwException()
      Ok("Should not see that!")
    } catch {
      case e: Throwable =>
        airbrake.notifyError(AirbrakeError(request, e))
        Ok(s"handbrake error sent for $e")
    }
  }
}
