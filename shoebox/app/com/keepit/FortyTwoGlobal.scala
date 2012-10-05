package com.keepit

import com.google.inject.Injector
import com.keepit.common.controller
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.controller.FortyTwoServices
import com.keepit.common.controller.ServiceType
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.ReportErrorsAction
import com.keepit.common.healthcheck.Heartbeat
import com.keepit.common.controller.CommonActions._
import com.keepit.inject._
import play.api._
import play.api.Mode
import play.api.Play.current
import play.api.mvc._
import play.api.mvc.Action
import play.api.mvc.Results.InternalServerError
import play.utils.Threads

abstract class FortyTwoGlobal(val mode: Mode.Mode) extends GlobalSettings with Logging {

  def injector: Injector
  protected lazy val healthcheck = injector.inject[Healthcheck]
  private lazy val scope = injector.inject[AppScope]
  
  override def beforeStart (app: Application): Unit = {
    val appName = app.configuration.getString("application.name").get
    log.info("starting the %s using %s".format(appName, getClass()))
    
    val dbs = app.configuration.getConfig("db").get.subKeys
    log.info("loading with dbs: %s".format(dbs.mkString(", ")))
    
    log.info("evolutionplugin: %s".format(app.configuration.getString("evolutionplugin")))
  }
  
  override def onBadRequest (request: RequestHeader, error: String): Result = {
    val errorId = ExternalId[Exception]()
    log.warn("bad request %s: %s on %s".format(errorId, error, request.path))
    InternalServerError("BAD REQUEST: %s: %s".format(errorId, error))
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
    log.info("Starting " + this)
    val baseUrl: String = current.configuration.getString("application.baseUrl").get
    log.info("FortyTwo %s Application version %s compiled at %s started on base URL: [%s]. Url is defined on conf/application.conf".format(
        FortyTwoServices.currentService, FortyTwoServices.currentVersion, FortyTwoServices.compilationTime, baseUrl))
    scope.onStart(app)
    if (app.mode != Mode.Test) healthcheck.reportStart()
  }
  
  override def onError(request: RequestHeader, ex: Throwable): Result = {
    //should we persist errors for later check?
    val globalError = healthcheck.addError(HealthcheckError(error = Some(ex), method = Some(request.method.toUpperCase()), path = Some(request.path), callType = Healthcheck.API))
    val error = if (Play.isDev) ex.toString() else "SEE LOGS FOR MORE INFO"
    val message = ("error [%s] on %s at path [%s] with query string %s".format(globalError.id, globalError.method, globalError.path, request.queryString.toString()), ex)
    ex.printStackTrace()
    log.error(message, ex)
    
    //should we json/structure the error message
    InternalServerError("error [%s] processing request %s on %s: %s".format(globalError.id, globalError.method, globalError.path, error))
    InternalServerError("error: %s".format(error))
  }

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    log.info(">>>>> Stopping " + this)
    try {
      if (app.mode != Mode.Test) healthcheck.reportStop()
      scope.onStop(app)
    } catch {
      case e => log.error("====================== error during onStop ===============================", e)
    }
  }
  
}