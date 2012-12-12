package com.keepit

import com.google.inject.Injector
import com.keepit.common.controller.CommonActions._
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.controller.ServiceType
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckPlugin
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

abstract class FortyTwoGlobal(val mode: Mode.Mode) extends GlobalSettings with Logging {

  def injector: Injector

  override def beforeStart (app: Application): Unit = {
    val conf = app.configuration
    val appName = conf.getString("application.name").get
    val dbs = conf.getConfig("db").get.subKeys

    println("starting app %s with dbs %s".format(
        appName, dbs.mkString(",")))

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
    println("Session cookie info: name [%s], http only [%s], signed [%s], max-age [%s], secure [%s]".format(
        Session.COOKIE_NAME, Session.httpOnly, Session.isSigned, Session.maxAge, Session.secure))
    if (app.mode != Mode.Test) {
      require(app.mode == mode, "Current mode %s is not allowed. Mode %s required for %s".format(app.mode, mode, this))
    }
    println("Starting %s".format(this))
    val baseUrl: String = current.configuration.getString("application.baseUrl").get
    var startMessage = "FortyTwo %s Application version %s compiled at %s started on base URL: [%s]. Url is defined on conf/application.conf".format(
        FortyTwoServices.currentService, FortyTwoServices.currentVersion, FortyTwoServices.compilationTime, baseUrl)
    log.info(startMessage)
    println(startMessage)
    injector.inject[AppScope].onStart(app)
    println("%s started".format(this))
    if (app.mode != Mode.Test   && app.mode != Mode.Dev) injector.inject[HealthcheckPlugin].reportStart()
  }

  override def onError(request: RequestHeader, ex: Throwable): Result = {
    //should we persist errors for later check?
    val globalError = injector.inject[HealthcheckPlugin].addError(HealthcheckError(error = Some(ex), method = Some(request.method.toUpperCase()), path = Some(request.path), callType = Healthcheck.API))
    val errorId = globalError.id.toString
//    val message = ("error [%s] on %s at path [%s] with query string %s".format(globalError.id, globalError.method, globalError.path, request.queryString.toString()), ex)
    ex.printStackTrace()
//    log.error(message, ex)

    //should we json/structure the error message
//    InternalServerError("error [%s] processing request %s on %s: %s".format(globalError.id, globalError.method, globalError.path, error))
    InternalServerError("error: %s".format(errorId))
  }

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    log.info(">>>>> Stopping " + this)
    try {
      if (app.mode != Mode.Test && app.mode != Mode.Dev) injector.inject[HealthcheckPlugin].reportStop()
      injector.inject[AppScope].onStop(app)
    } catch {
      case e => log.error("====================== error during onStop ===============================", e)
    }
  }

}
