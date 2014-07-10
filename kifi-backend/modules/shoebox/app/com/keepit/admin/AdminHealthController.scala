package com.keepit.controllers.admin

import com.keepit.common.time.RichDateTime
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.cache.GlobalCacheStatistics
import com.keepit.common.healthcheck._

import scala.util.Random

import play.api.Play.current
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial

import views.html

import com.keepit.common.controller.{ AdminController, ActionAuthenticator }
import com.google.inject.Inject

class AdminHealthController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  healthcheckPlugin: HealthcheckPlugin,
  services: FortyTwoServices,
  airbrake: AirbrakeNotifier,
  globalCacheStatistics: GlobalCacheStatistics)
    extends AdminController(actionAuthenticator) {

  def serviceView = AdminHtmlAction.authenticated { implicit request =>
    val errorCount = healthcheckPlugin.errorCount
    val recentErrors = healthcheckPlugin.errors()
    val cacheStats = globalCacheStatistics.getStatistics
    val (totalHits, totalMisses, totalSets) = (cacheStats.map(_._2).sum, cacheStats.map(_._3).sum, cacheStats.map(_._4).sum)
    Ok(html.admin.serverInfo(services.currentService, services.currentVersion, services.compilationTime.toStandardTimeString,
      services.started.toStandardTimeString, errorCount, recentErrors, cacheStats, totalHits, totalMisses, totalSets))
  }

  def getErrors() = AdminHtmlAction.authenticated { implicit request =>
    Ok(healthcheckPlugin.errors().mkString("\n"))
  }

  def reportErrors() = AdminHtmlAction.authenticated { implicit request =>
    healthcheckPlugin.reportErrors()
    Ok("reported")
  }

  def resetErrorCount() = AdminHtmlAction.authenticated { implicit request =>
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

  def causeAirbrakeError(message: String) = Action { implicit request =>
    try {
      throwException()
      Ok("Should not see that!")
    } catch {
      case e: Throwable =>
        airbrake.notify(AirbrakeError.incoming(request, e, message))
        Ok(s"airbrake error sent for $e")
    }
  }

  def reportDeployment() = Action { implicit request =>
    airbrake.reportDeployment()
    Ok("deployment reported to airbrake")
  }
}
