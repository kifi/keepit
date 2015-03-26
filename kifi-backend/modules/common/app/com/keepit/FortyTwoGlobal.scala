package com.keepit

import java.io.File
import com.keepit.common.actor.ActorSystemPlugin
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.controller._
import com.keepit.common.strings._
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.{ HealthcheckPlugin, AirbrakeNotifier, AirbrakeError, BenchmarkRunner, MemoryUsageMonitor }
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.service.{ FortyTwoServices, ServiceStatus }
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.inject._
import com.typesafe.config.ConfigFactory
import com.keepit.common.time.{ currentDateTime, DEFAULT_DATE_TIME_ZONE, RichDateTime }
import play.api.libs.json.Json
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration.Duration
import scala.collection.JavaConversions._
import play.api._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.Results._
import play.api.mvc._
import play.modules.statsd.api.StatsdFilter
import play.utils.Threads
import scala.util.Try
import scala.util.control.NonFatal
import com.amazonaws.services.elasticloadbalancing.model._
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.keepit.common.shutdown.ShutdownCommander
import java.util.concurrent.atomic.AtomicLong
import net.codingwell.scalaguice.InjectorExtensions._

abstract class FortyTwoGlobal(val mode: Mode.Mode)
    extends WithFilters(new LoggingFilter()) with Logging with EmptyInjector {

  //used to identify instance of application. used to debug in test mode
  val globalId: ExternalId[FortyTwoGlobal] = ExternalId()
  log.debug(s"########## starting FortyTwoGlobal $globalId")

  @volatile private var pluginsStarted: Boolean = false

  override def getControllerInstance[A](clazz: Class[A]) = try {
    injector.getInstance(clazz)
  } catch {
    case e: Throwable =>
      injector.instance[AirbrakeNotifier].notify(e)
      throw e
  }

  override def beforeStart(app: Application): Unit = {
    val conf = app.configuration
    val appName = conf.getString("application.name").get
    conf.getConfig("db") match {
      case Some(dbs) => println(s"starting app $appName with dbs ${dbs.subKeys.mkString(",")}")
      case None => println(s"starting app $appName without db")
    }
  }

  private def registerToLoadBalancer() {
    val amazonInstanceInfo = injector.instance[AmazonInstanceInfo]
    amazonInstanceInfo.loadBalancer map { loadBalancer =>
      val elbClient = injector.instance[AmazonElasticLoadBalancingClient]
      val instance = new Instance(amazonInstanceInfo.instanceId.id)
      val request = new RegisterInstancesWithLoadBalancerRequest(loadBalancer, Seq(instance))
      try {
        elbClient.registerInstancesWithLoadBalancer(request)
        println(s"[${currentDateTime.toStandardTimeString}] Registered instance ${amazonInstanceInfo.instanceId} with load balancer $loadBalancer")
      } catch {
        case t: Throwable =>
          //todo(martin): find a solution
          //injector.instance[AirbrakeNotifier].panic(s"Error registering instance ${amazonInstanceInfo.instanceId} with load balancer $loadBalancer: $t")
          println(s"[${currentDateTime.toStandardTimeString}] Error registering instance ${amazonInstanceInfo.instanceId} with load balancer $loadBalancer: $t")
          Play.stop()
          Thread.sleep(10000)
          System.exit(1)
      }
    } getOrElse println(s"[${currentDateTime.toStandardTimeString}] No load balancer registered for instance ${amazonInstanceInfo.instanceId}")
  }

  override def onStart(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    if (app.mode != Mode.Test) {
      require(app.mode == mode, "Current mode %s is not allowed. Mode %s required for %s".format(app.mode, mode, this))
    }
    val services = injector.instance[FortyTwoServices]
    val startMessage = ">>>>>>>>>> FortyTwo [%s] service %s Application version %s compiled at %s started on base URL: [%s]. Url is defined on conf/application.conf".format(
      this.getClass.getSimpleName, services.currentService, services.currentVersion, services.compilationTime, services.baseUrl)
    log.info(s"[${currentDateTime.toStandardTimeString}] " + startMessage)

    val disableRegistration = sys.props.getOrElse("service.register.disable", "false").toBoolean // directly use sys.props to be consistent; uptake injected config later
    val serviceDiscoveryOpt: Option[ServiceDiscovery] = if (disableRegistration) None else {
      val amazonInstanceInfo = injector.instance[AmazonInstanceInfo]
      log.info(s"Amazon up! $amazonInstanceInfo")
      val serviceDiscovery = injector.instance[ServiceDiscovery]
      serviceDiscovery.register()
      serviceDiscovery.forceUpdate()
      Some(serviceDiscovery)
    }

    Try(injector.instance[ActorSystemPlugin].onStart()) // start actor system
    if (app.mode != Mode.Test) {
      injector.instance[AppScope].onStart(app)
      pluginsStarted = true
    }

    if (app.mode != Mode.Test && app.mode != Mode.Dev) {
      statsd.incrementOne("deploys", ALWAYS)
      injector.instance[AirbrakeNotifier].reportDeployment()
      injector.instance[HealthcheckPlugin].warmUp(injector.instance[BenchmarkRunner])
    }

    serviceDiscoveryOpt map { serviceDiscovery =>
      val selfCheckPassed: Boolean = Await.result(serviceDiscovery.startSelfCheck(), Duration.Inf)
      if (!selfCheckPassed) {
        log.error("STARTUP SELF CHECK FAILED!")
      }
      serviceDiscovery.forceUpdate()
    }

    injector.instance[MemoryUsageMonitor].start()

    registerToLoadBalancer()
  }

  // Get a file within the .fortytwo folder in the user's home directory
  def getUserFile(filename: String): File =
    new File(Seq(System.getProperty("user.home"), ".fortytwo", filename).mkString(File.separator))

  override def onLoadConfig(config: Configuration, path: File, classloader: ClassLoader, mode: Mode.Mode) = {
    val localConfig = Configuration(ConfigFactory.parseFile(getUserFile("local.conf")))
    val overrideConfig = Configuration(ConfigFactory.parseFile(new File(path, "override.conf"))) // Configuration override (erased with the next deploy)
    super.onLoadConfig(config ++ localConfig ++ overrideConfig, path, classloader, mode)
  }

  override def onBadRequest(request: RequestHeader, error: String): Future[Result] = Future.successful {
    val errorId = ExternalId[Exception]()
    val msg = s"BAD REQUEST: $errorId: [$error] on ${request.method}:${request.path} query: ${request.queryString.mkString("::")}"
    log.warn(msg)

    if (mode == Mode.Test) {
      throw new Exception(s"error [$msg] on $request")
    }

    if (request.path.startsWith("/internal/")) {
      allowCrossOrigin(request, BadRequest(msg))
    } else if (speaksJson(request)) {
      allowCrossOrigin(request, BadRequest(Json.obj(
        "error" -> errorId.id,
        "status" -> "bad_request",
        "request" -> s"${request.method}:${request.path} query: ${request.queryString.mkString("::")}"
      )))
    } else {
      allowCrossOrigin(request, BadRequest(views.html.error.internalError(errorId.id)))
    }
  }

  override def onRouteRequest(request: RequestHeader) = super.onRouteRequest(request).orElse {
    // todo: Check if there is a handler before 301ing.
    Some(request.path).filter(_.endsWith("/")).map(p => Action(Results.MovedPermanently(p.dropRight(1))))
  }

  override def onHandlerNotFound(request: RequestHeader): Future[Result] = Future.successful {
    val errorId = ExternalId[Exception]()
    val msg = s"Handler Not Found ${errorId.id}: on ${request.method} ${request.path}"
    log.warn(msg)

    if (request.path.startsWith("/internal/")) {
      allowCrossOrigin(request, NotFound(msg))
    } else if (speaksJson(request)) {
      allowCrossOrigin(request, NotFound(Json.obj(
        "error" -> errorId.id,
        "status" -> "not_found",
        "request" -> s"${request.method}:${request.path} query: ${request.queryString.mkString("::")}"
      )))
    } else {
      allowCrossOrigin(request, NotFound(views.html.error.notFound(request.path)))
    }
  }

  private[this] val lastAlert = new AtomicLong(-1)

  private def serviceDiscoveryHandleError(): Unit = {
    val now = System.currentTimeMillis()
    val last = lastAlert.get
    if (now - last > 600000) { //10 minute
      if (lastAlert.compareAndSet(last, now)) {
        val serviceDiscovery = injector.instance[ServiceDiscovery]
        serviceDiscovery.changeStatus(ServiceStatus.SICK)
        serviceDiscovery.startSelfCheck()
        serviceDiscovery.forceUpdate()
      }
    }
  }

  override def onError(request: RequestHeader, ex: Throwable): Future[Result] = Future {
    try {
      val errorId: ExternalId[_] = ex match {
        case reported: ReportedException => reported.id
        case _ => injector.instance[AirbrakeNotifier].notify(AirbrakeError.incoming(request, ex)).id
      }
      val cause = if (Option(ex.getCause).isDefined) ex.getCause else ex
      val bareErrorMessage = s"Play onError (${errorId.id} ): ${cause.toString} : ${request.path} : ${request.queryString}"
      val errorMessage = request match {
        case req: WrappedRequest[_] => s"$bareErrorMessage : with body: ${req.body.toString}"
        case _ => bareErrorMessage
      }
      System.err.println(errorMessage)
      ex.printStackTrace()
      log.error(errorMessage)
      serviceDiscoveryHandleError()
      if (request.path.startsWith("/internal/")) {
        //todo(eishay) consider use the original ex.getCause instead
        val message = s"${ex.getClass.getSimpleName}:${ex.getMessage.abbreviate(100)}, errorId:${errorId.id}"
        allowCrossOrigin(request, InternalServerError(message))
      } else if (speaksJson(request)) {
        allowCrossOrigin(request, InternalServerError(Json.obj(
          "error" -> errorId.id,
          "status" -> "internal_error",
          "request" -> s"${request.method}:${request.path} query: ${request.queryString.mkString("::")}"
        )))
      } else {
        allowCrossOrigin(request, InternalServerError(views.html.error.internalError(errorId.id)))
      }
    } catch {
      case NonFatal(e) =>
        val id = ExternalId[Exception]()
        Logger.error(s"Error while rendering default error page: ${id.id}", e)
        InternalServerError(views.html.error.internalError(id.id))
    }
  }

  @volatile private var announcedStopping: Boolean = false

  def announceStopping(app: Application): Unit = if (!announcedStopping) synchronized {
    if (!announcedStopping) { //double check on entering sync block
      injector.instance[ShutdownCommander].shutdown()
      if (mode == Mode.Prod) {
        try {
          val serviceDiscovery = injector.instance[ServiceDiscovery]
          serviceDiscovery.changeStatus(ServiceStatus.STOPPING)
          println(s"[${currentDateTime.toStandardTimeString}] [announceStopping] let clients and ELB know we're stopping")
          Thread.sleep(18000)
          println(s"[${currentDateTime.toStandardTimeString}] [announceStopping] moving on")
        } catch {
          case t: Throwable => println(s"[${currentDateTime.toStandardTimeString}] error announcing service stop via explicit shutdown hook: $t")
        }
      }
      try {
        if (pluginsStarted) {
          injector.instance[AppScope].onStop(app)
          if (app.mode != Mode.Test) {
            Try(injector.instance[ActorSystemPlugin].onStop())
          }
          pluginsStarted = false
        }
      } catch {
        case e: Throwable =>
          val errorMessage = "====================== error during onStop ==============================="
          println(errorMessage)
          e.printStackTrace()
          log.error(errorMessage, e)
      } finally {
        if (mode == Mode.Prod) {
          println(s"[${currentDateTime.toStandardTimeString}] <<<<<< about to pause and let the system shut down")
          Thread.sleep(3000)
          println(s"[${currentDateTime.toStandardTimeString}] <<<<<< done sleeping, continue with termination")
        }
      }
      announcedStopping = true
    }
  }

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    val serviceDiscovery = injector.instance[ServiceDiscovery]
    announceStopping(app)
    val stopMessage = s"[${currentDateTime.toStandardTimeString}] <<<<<<<<<< Stopping " + this.getClass.getSimpleName
    println(stopMessage)
    log.info(stopMessage)
    serviceDiscovery.unRegister()
  }

  private def allowCrossOrigin(request: RequestHeader, result: Result): Result = { // for kifi.com/site dev
    request.headers.get("Origin").filter { uri =>
      val host = URI.parse(uri).toOption.flatMap(_.host).map(_.toString).getOrElse("")
      host.endsWith("ezkeep.com") || host.endsWith("kifi.com") || host.endsWith("browserstack.com")
    }.map { h =>
      result.withHeaders(
        "Access-Control-Allow-Origin" -> h,
        "Access-Control-Allow-Credentials" -> "true")
    }.getOrElse(result)
  }

  private def speaksJson(request: RequestHeader) = {
    // todo: check if request.accepts works better
    request.path.startsWith("/site/") || request.path.startsWith("/m/") || request.path.startsWith("/ext/") || request.path.startsWith("/search/") || request.path.startsWith("/eliza/")
  }

}
