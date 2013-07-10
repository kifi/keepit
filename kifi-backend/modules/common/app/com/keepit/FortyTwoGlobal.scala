package com.keepit

import com.keepit.common.controller._
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin}
import com.keepit.common.logging.Logging
import com.keepit.common.service.{FortyTwoServices,ServiceStatus}
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.inject._
import play.api._
import play.api.mvc.Results._
import play.api.mvc._
import play.modules.statsd.api.{Statsd, StatsdFilter}
import play.utils.Threads
import com.keepit.common.amazon.AmazonInstanceInfo

abstract class FortyTwoGlobal(val mode: Mode.Mode)
    extends WithFilters(LoggingFilter, new StatsdFilter()) with Logging with EmptyInjector {

  override def getControllerInstance[A](clazz: Class[A]) = try {
    injector.getInstance(clazz)
  } catch {
    case e: Throwable =>
      injector.instance[HealthcheckPlugin].addError(HealthcheckError(error = Some(e), callType = Healthcheck.API))
      throw e
  }

  override def beforeStart (app: Application): Unit = {
    val conf = app.configuration
    val appName = conf.getString("application.name").get
    val dbs = conf.getConfig("db").get.subKeys
    println("starting app %s with dbs %s".format(appName, dbs.mkString(",")))
  }

  override def onBadRequest(request: RequestHeader, error: String): Result = {
    val errorId = ExternalId[Exception]()
    val msg = "BAD REQUEST: %s: [%s] on %s:%s query: %s".format(errorId, error, request.method, request.path, request.queryString.mkString("::"))
    log.warn(msg)
    BadRequest(msg)
  }

  override def onHandlerNotFound(request: RequestHeader): Result = {
    val errorId = ExternalId[Exception]()
    log.warn("Handler Not Found %s: on %s".format(errorId, request.path))
    NotFound("NO HANDLER: %s".format(errorId))
  }

  override def onStart(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    if (app.mode != Mode.Test) {
      require(app.mode == mode, "Current mode %s is not allowed. Mode %s required for %s".format(app.mode, mode, this))
    }
    val services = injector.instance[FortyTwoServices]
    val startMessage = ">>>>>>>>>> FortyTwo [%s] service %s Application version %s compiled at %s started on base URL: [%s]. Url is defined on conf/application.conf".format(
        this, services.currentService, services.currentVersion, services.compilationTime, services.baseUrl)
    log.info(startMessage)
    injector.instance[AppScope].onStart(app)
    if (app.mode != Mode.Test && app.mode != Mode.Dev) {
      Statsd.increment("deploys", 42)
      injector.instance[HealthcheckPlugin].reportStart()
      injector.instance[HealthcheckPlugin].warmUp()
    }
    
    val amazonInstanceInfo = injector.instance[AmazonInstanceInfo]
    log.info(s"Amazon up! $amazonInstanceInfo")
    val serviceDiscovery = injector.instance[ServiceDiscovery]
    serviceDiscovery.register()
    serviceDiscovery.startSelfCheck()

  }

  override def onError(request: RequestHeader, ex: Throwable): Result = {
    val serviceDiscovery = injector.instance[ServiceDiscovery]
    serviceDiscovery.changeStatus(ServiceStatus.SICK) 
    val errorId = ex match {
      case reported: ReportedException =>
        reported.id
      case _ =>
        injector.instance[HealthcheckPlugin].addError(HealthcheckError(error = Some(ex), method = Some(request.method.toUpperCase()), path = Some(request.path), callType = Healthcheck.API)).id
    }
    ex.printStackTrace()
    serviceDiscovery.startSelfCheck
    InternalServerError("error: %s".format(errorId))
  }

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    val serviceDiscovery = injector.instance[ServiceDiscovery]
    serviceDiscovery.changeStatus(ServiceStatus.STOPPING)
    val stopMessage = "<<<<<<<<<< Stopping " + this
    println(stopMessage)
    log.info(stopMessage)
    try {
      if (app.mode != Mode.Test && app.mode != Mode.Dev) injector.instance[HealthcheckPlugin].reportStop()
      injector.instance[AppScope].onStop(app)
    } catch {
      case e: Throwable =>
        val errorMessage = "====================== error during onStop ==============================="
        println(errorMessage)
        e.printStackTrace
        log.error(errorMessage, e)
    }
    serviceDiscovery.unRegister()
  }

}
