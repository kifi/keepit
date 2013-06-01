package com.keepit

import com.google.inject.{Stage, Guice, Module, Injector}
import com.keepit.common.controller.ReportedException
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin}
import com.keepit.common.logging.Logging
import com.keepit.common.service.FortyTwoServices
import com.keepit.inject._
import java.util.concurrent.atomic.AtomicBoolean
import play.api._
import play.api.mvc.Results._
import play.api.mvc._
import play.modules.statsd.api.StatsdFilter
import play.utils.Threads

abstract class FortyTwoGlobal(val mode: Mode.Mode)
    extends GlobalSettings /* extends WithFilters(new StatsdFilter()) */ with Logging {

  implicit def richInjector(injector: Injector) = new RichInjector(injector)

  def modules: Seq[Module]

  private val creatingInjector = new AtomicBoolean(false)

  private val _initialized = new AtomicBoolean(false)
  def initialized = _initialized.get

  /**
   * While executing the code block that return the injector,
   * we found few times that one of the injected components was using inject[Foo] during their construction
   * instead using the constructor injector (a bug).
   * In that case the application will try to access the injector - that is being created at this moment.
   * Then scala executes the injector code block again which eventually creates an infinit stack trace and out of stack space.
   * The exception is to help us understand the problem.
   * As we kill the inject[Foo] pattern then there will be no use for the creatingInjector.
   * We'll still want the lazy val since the injector is depending on things from the application (like the configuration info)
   * and we don't want to instantiate it until the onStart(app: Application) is executed.
  */
  lazy val injector: Injector = {
    if (creatingInjector.getAndSet(true)) throw new Exception("Injector is being created!")
    val injector = mode match {
      case Mode.Dev => Guice.createInjector(Stage.DEVELOPMENT, modules: _*)
      case Mode.Prod => Guice.createInjector(Stage.PRODUCTION, modules: _*)
      case Mode.Test => Guice.createInjector(Stage.DEVELOPMENT, modules: _*)
      case m => throw new IllegalStateException(s"Unknown mode $m")
    }
    _initialized.set(true)
    injector
  }

  override def getControllerInstance[A](clazz: Class[A]) = try {
    injector.getInstance(clazz)
  } catch {
    case e: Throwable =>
      injector.inject[HealthcheckPlugin].addError(HealthcheckError(error = Some(e), callType = Healthcheck.API))
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
