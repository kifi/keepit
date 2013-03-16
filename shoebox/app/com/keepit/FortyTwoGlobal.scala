package com.keepit

import com.google.inject.{Stage, Guice, Module, Injector}
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.controller.ServiceType
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin}
import com.keepit.common.logging.Logging
import com.keepit.common.controller
import com.keepit.inject._
import play.api.Play.current
import play.api.mvc.Results.InternalServerError
import play.api.mvc._
import play.api._
import play.api.Mode
import play.utils.Threads
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.controller.ReportedException

abstract class FortyTwoGlobal(val mode: Mode.Mode) extends GlobalSettings with Logging {

  def modules: Seq[Module]

  lazy val injector: Injector = mode match {
    case Mode.Dev => Guice.createInjector(Stage.DEVELOPMENT, modules: _*)
    case Mode.Prod => Guice.createInjector(Stage.PRODUCTION, modules: _*)
    case Mode.Test => Guice.createInjector(Stage.DEVELOPMENT, modules: _*)
    case m => throw new IllegalStateException(s"Unknown mode $m")
  }

  override def getControllerInstance[A](clazz: Class[A]) = injector.getInstance(clazz)

  override def beforeStart (app: Application): Unit = {
    val conf = app.configuration
    val appName = conf.getString("application.name").get
    val dbs = conf.getConfig("db").get.subKeys
    println("starting app %s with dbs %s".format(appName, dbs.mkString(",")))
  }

  override def onBadRequest (request: RequestHeader, error: String): Result = {
    val errorId = ExternalId[Exception]()
    val msg = "BAD REQUEST: %s: [%s] on %s:%s query: %s".format(errorId, error, request.method, request.path, request.queryString.mkString("::"))
    log.warn(msg)
    InternalServerError(msg)
  }

  override def onHandlerNotFound (request: RequestHeader): Result = {
    val errorId = ExternalId[Exception]()
    log.warn("Handler Not Found %s: on %s".format(errorId, request.path))
    InternalServerError("NO HANDLER: %s".format(errorId))
  }

  override def onStart(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    if (app.mode != Mode.Test) {
      require(app.mode == mode, "Current mode %s is not allowed. Mode %s required for %s".format(app.mode, mode, this))
    }
    val services = injector.inject[FortyTwoServices]
    val startMessage = ">>>>>>>>>> FortyTwo [%s] service %s Application version %s compiled at %s started on base URL: [%s]. Url is defined on conf/application.conf".format(
        this, services.currentService, services.currentVersion, services.compilationTime, services.baseUrl)
    log.info(startMessage)
    println(startMessage)
    injector.inject[AppScope].onStart(app)
    if (app.mode != Mode.Test && app.mode != Mode.Dev) injector.inject[HealthcheckPlugin].reportStart()
  }

  override def onError(request: RequestHeader, ex: Throwable): Result = {
    val errorId = ex match {
      case reported: ReportedException =>
        reported.id
      case _ =>
        injector.inject[HealthcheckPlugin].addError(HealthcheckError(error = Some(ex), method = Some(request.method.toUpperCase()), path = Some(request.path), callType = Healthcheck.API)).id
    }
    ex.printStackTrace()
    InternalServerError("error: %s".format(errorId))
  }

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    val stopMessage = "<<<<<<<<<< Stopping " + this
    println(stopMessage)
    log.info(stopMessage)
    try {
      if (app.mode != Mode.Test && app.mode != Mode.Dev) injector.inject[HealthcheckPlugin].reportStop()
      injector.inject[AppScope].onStop(app)
    } catch {
      case e: Throwable => 
        val errorMessage = "====================== error during onStop ==============================="
        println(errorMessage)
        e.printStackTrace
        log.error(errorMessage, e)
    }
  }

}
