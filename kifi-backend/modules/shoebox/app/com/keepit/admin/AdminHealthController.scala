package com.keepit.controllers.admin

import java.io.{ StringWriter, ByteArrayOutputStream, PrintWriter }

import com.keepit.FortyTwoGlobal
import com.keepit.common.time.RichDateTime
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.cache.GlobalCacheStatistics
import com.keepit.common.healthcheck._
import com.google.inject.{ Guice, Injector, Inject };
import com.google.inject.grapher.graphviz.GraphvizGrapher;
import com.google.inject.grapher.graphviz.GraphvizModule
import play.api.Play
import play.api.Application;

import scala.util.Random

import play.api.Play.current
import play.api.mvc.Action
import play.api.mvc.Controller
import securesocial.core.SecureSocial

import views.html

import com.keepit.common.controller.{ UserActionsHelper, AdminUserActions }

class AdminHealthController @Inject() (
  val userActionsHelper: UserActionsHelper,
  healthcheckPlugin: HealthcheckPlugin,
  services: FortyTwoServices,
  airbrake: AirbrakeNotifier,
  globalCacheStatistics: GlobalCacheStatistics)
    extends AdminUserActions {

  def serviceView = AdminUserPage { implicit request =>
    val errorCount = healthcheckPlugin.errorCount
    val recentErrors = healthcheckPlugin.errors()
    val cacheStats = globalCacheStatistics.getStatistics
    val (totalHits, totalMisses, totalSets) = (cacheStats.map(_._2).sum, cacheStats.map(_._3).sum, cacheStats.map(_._4).sum)
    Ok(html.admin.serverInfo(services.currentService, services.currentVersion, services.compilationTime.toStandardTimeString,
      services.started.toStandardTimeString, errorCount, recentErrors, cacheStats, totalHits, totalMisses, totalSets))
  }

  def getErrors() = AdminUserPage { implicit request =>
    Ok(healthcheckPlugin.errors().mkString("\n"))
  }

  //see https://github.com/google/guice/wiki/Grapher
  def getGuiceGraph() = AdminUserPage { implicit request =>
    val global = Play.current.global.asInstanceOf[FortyTwoGlobal] // fail hard
    val injector = global.injector
    val stringWriter = new StringWriter()
    val writer = new PrintWriter(stringWriter)
    val grapher = Guice.createInjector(new GraphvizModule()).getInstance(classOf[GraphvizGrapher])
    grapher.setOut(writer)
    grapher.setRankdir("TB");
    grapher.graph(injector);
    Ok(stringWriter.toString)
  }

  def reportErrors() = AdminUserPage { implicit request =>
    healthcheckPlugin.reportErrors()
    Ok("reported")
  }

  def resetErrorCount() = AdminUserPage { implicit request =>
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
