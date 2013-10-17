package com.keepit

import java.io.File

import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.controller._
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, AirbrakeNotifier, HealthcheckError, AirbrakeError, BenchmarkRunner}
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.service.{FortyTwoServices,ServiceStatus}
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.inject._
import com.typesafe.config.ConfigFactory

import play.api._
import play.api.mvc.Results._
import play.api.mvc._
import play.modules.statsd.api.{Statsd, StatsdFilter}
import play.utils.Threads

abstract class FortyTwoGlobal(val mode: Mode.Mode)
    extends WithFilters(new LoggingFilter(), new StatsdFilter()) with Logging with EmptyInjector {

  override def getControllerInstance[A](clazz: Class[A]) = try {
    injector.getInstance(clazz)
  } catch {
    case e: Throwable =>
      injector.instance[HealthcheckPlugin].addError(HealthcheckError(error = Some(e), callType = Healthcheck.API))
      throw e
  }

  override def beforeStart(app: Application): Unit = {
    val conf = app.configuration
    val appName = conf.getString("application.name").get
    val dbs = conf.getConfig("db").get.subKeys
    println("starting app %s with dbs %s".format(appName, dbs.mkString(",")))
  }

  override def onStart(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    if (app.mode != Mode.Test) {
      require(app.mode == mode, "Current mode %s is not allowed. Mode %s required for %s".format(app.mode, mode, this))
    }
    val services = injector.instance[FortyTwoServices]
    val startMessage = ">>>>>>>>>> FortyTwo [%s] service %s Application version %s compiled at %s started on base URL: [%s]. Url is defined on conf/application.conf".format(
        this, services.currentService, services.currentVersion, services.compilationTime, services.baseUrl)
    log.info(startMessage)

    val amazonInstanceInfo = injector.instance[AmazonInstanceInfo]
    log.info(s"Amazon up! $amazonInstanceInfo")
    val serviceDiscovery = injector.instance[ServiceDiscovery]
    serviceDiscovery.register()

    injector.instance[AppScope].onStart(app)

    val prod = app.mode != Mode.Test && app.mode != Mode.Dev
    if (prod) {
      injector.instance[HealthcheckPlugin].warmUp(injector.instance[BenchmarkRunner])
    }
    serviceDiscovery.startSelfCheck()
    serviceDiscovery.forceUpdate()
    if (prod) {
      Statsd.increment("deploys", 42)
      injector.instance[AirbrakeNotifier].reportDeployment()
      injector.instance[HealthcheckPlugin].reportStart()
    }
  }

  // Get a file within the .fortytwo folder in the user's home directory
  def getUserFile(filename: String): File =
    new File(Seq(System.getProperty("user.home"), ".fortytwo", filename).mkString(File.separator))

  override def onLoadConfig(config: Configuration, path: File, classloader: ClassLoader, mode: Mode.Mode) = {
    val localConfig = Configuration(ConfigFactory.parseFile(getUserFile("local.conf")))
    super.onLoadConfig(config ++ localConfig, path, classloader, mode)
  }
  override def onBadRequest(request: RequestHeader, error: String): Result = {
    val errorId = ExternalId[Exception]()
    val msg = "BAD REQUEST: %s: [%s] on %s:%s query: %s".format(errorId, error, request.method, request.path, request.queryString.mkString("::"))
    log.warn(msg)
    allowCrossOrigin(request, BadRequest(msg))
  }

  override def onRouteRequest(request: RequestHeader) = super.onRouteRequest(request).orElse {
    // todo: Check if there is a handler before 301ing.
    Some(request.path).filter(_.endsWith("/")).map(p => Action(Results.MovedPermanently(p.dropRight(1))))
  }

  override def onHandlerNotFound(request: RequestHeader): Result = {
    val errorId = ExternalId[Exception]()
    log.warn("Handler Not Found %s: on %s".format(errorId, request.path))
    allowCrossOrigin(request, NotFound("NO HANDLER: %s".format(errorId)))
  }

  override def onError(request: RequestHeader, ex: Throwable): Result = {
    val serviceDiscovery = injector.instance[ServiceDiscovery]
    serviceDiscovery.changeStatus(ServiceStatus.SICK)
    val errorId = ex match {
      case reported: ReportedException => reported.id
      case _ => injector.instance[AirbrakeNotifier].notify(AirbrakeError.incoming(request, ex))
    }
    ex.printStackTrace()
    serviceDiscovery.startSelfCheck()
    serviceDiscovery.forceUpdate()
    allowCrossOrigin(request, InternalServerError("error: %s".format(errorId)))
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
    finally {
      serviceDiscovery.unRegister()
    }
  }

  private def allowCrossOrigin(request: RequestHeader, result: Result): Result = {  // for kifi.com/site dev
    request.headers.get("Origin").filter { uri =>
      val host = URI.parse(uri).toOption.flatMap(_.host).map(_.toString).getOrElse("")
      host.endsWith("ezkeep.com") || host.endsWith("kifi.com") || host.endsWith("browserstack.com")
    }.map { h =>
      result.withHeaders(
        "Access-Control-Allow-Origin" -> h,
        "Access-Control-Allow-Credentials" -> "true")
    }.getOrElse(result)
  }

}
