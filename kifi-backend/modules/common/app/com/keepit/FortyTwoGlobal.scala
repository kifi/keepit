package com.keepit

import java.io.File

import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.controller._
import com.keepit.common.strings._
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, AirbrakeNotifier, AirbrakeError, BenchmarkRunner, MemoryUsageMonitor}
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.service.{FortyTwoServices,ServiceStatus}
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.inject._
import com.typesafe.config.ConfigFactory

import scala.concurrent.{Future, Await}
import scala.concurrent.duration.Duration
import scala.collection.JavaConversions._

import play.api._
import play.api.mvc.Results._
import play.api.mvc._
import play.modules.statsd.api.{Statsd, StatsdFilter}
import play.utils.Threads
import scala.util.control.NonFatal
import com.amazonaws.services.elasticloadbalancing.model._
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
import com.amazonaws.AmazonClientException
import com.amazonaws.services.ec2.AmazonEC2Client

abstract class FortyTwoGlobal(val mode: Mode.Mode)
    extends WithFilters(new LoggingFilter(), new StatsdFilter()) with Logging with EmptyInjector {

  //used to identify instance of applciation. used to debug intest mode
  val globalId: ExternalId[FortyTwoGlobal] = ExternalId()
  log.debug(s"########## starting FortyTwoGlobal $globalId")

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
        log.info(s"Registered instance ${amazonInstanceInfo.instanceId} with load balancer $loadBalancer")
      } catch {
        case t:Throwable => {
          injector.instance[AirbrakeNotifier].panic(s"Error registering instance ${amazonInstanceInfo.instanceId} with load balancer $loadBalancer: $t")
          Play.stop()
        }
      }
    } getOrElse log.info(s"No load balancer registered for instance ${amazonInstanceInfo.instanceId}")
  }

  private def deregisterFromLoadBalancer() {
    val amazonInstanceInfo = injector.instance[AmazonInstanceInfo]
    amazonInstanceInfo.loadBalancer map { loadBalancer =>
      val elbClient = injector.instance[AmazonElasticLoadBalancingClient]
      val instance = new Instance(amazonInstanceInfo.instanceId.id)
      val request = new DeregisterInstancesFromLoadBalancerRequest(loadBalancer, Seq(instance))
      try {
        elbClient.deregisterInstancesFromLoadBalancer(request)
        log.info(s"Deregistered instance ${amazonInstanceInfo.instanceId} from load balancer $loadBalancer")
      } catch {
        case t:AmazonClientException => {
          injector.instance[AirbrakeNotifier].notify(s"Error deregistering instance ${amazonInstanceInfo.instanceId} from load balancer $loadBalancer: $t - Delaying shutdown for a few seconds...")
          Thread.sleep(18000)
        }
      }
    } getOrElse log.info(s"No load balancer registered for instance ${amazonInstanceInfo.instanceId}")
  }

  override def onStart(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    if (app.mode != Mode.Test) {
      require(app.mode == mode, "Current mode %s is not allowed. Mode %s required for %s".format(app.mode, mode, this))
    }
    val services = injector.instance[FortyTwoServices]
    val startMessage = ">>>>>>>>>> FortyTwo [%s] service %s Application version %s compiled at %s started on base URL: [%s]. Url is defined on conf/application.conf".format(
        this, services.currentService, services.currentVersion, services.compilationTime, services.baseUrl)
    log.info(startMessage)

    val disableRegistration = sys.props.getOrElse("service.register.disable", "false").toBoolean // directly use sys.props to be consistent; uptake injected config later
    val serviceDiscoveryOpt:Option[ServiceDiscovery] = if (disableRegistration) None else {
      val amazonInstanceInfo = injector.instance[AmazonInstanceInfo]
      log.info(s"Amazon up! $amazonInstanceInfo")
      val serviceDiscovery = injector.instance[ServiceDiscovery]
      serviceDiscovery.register()
      serviceDiscovery.forceUpdate()
      Some(serviceDiscovery)
    }

    injector.instance[AppScope].onStart(app)
    if (app.mode != Mode.Test && app.mode != Mode.Dev) {
      Statsd.increment("deploys", 42)
      injector.instance[AirbrakeNotifier].reportDeployment()
      injector.instance[HealthcheckPlugin].reportStart()
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
    super.onLoadConfig(config ++ localConfig, path, classloader, mode)
  }
  override def onBadRequest(request: RequestHeader, error: String): Future[SimpleResult] = {
    val errorId = ExternalId[Exception]()
    val msg = s"BAD REQUEST: $errorId: [$error] on ${request.method}:${request.path} query: ${request.queryString.mkString("::")}"
    log.warn(msg)
    if (mode == Mode.Test) {
      throw new Exception(s"error [$msg] on $request")
    }
    Future.successful(allowCrossOrigin(request, BadRequest(msg)))
  }

  override def onRouteRequest(request: RequestHeader) = super.onRouteRequest(request).orElse {
    // todo: Check if there is a handler before 301ing.
    Some(request.path).filter(_.endsWith("/")).map(p => Action(Results.MovedPermanently(p.dropRight(1))))
  }

  override def onHandlerNotFound(request: RequestHeader): Future[SimpleResult] = {
    val errorId = ExternalId[Exception]()
    log.warn("Handler Not Found %s: on %s".format(errorId, request.path))
    Future.successful(allowCrossOrigin(request, NotFound("NO HANDLER: %s".format(errorId))))
  }

  @volatile private var lastAlert: Long = -1

  private def serviceDiscoveryHandleError(): Unit = {
    val now = System.currentTimeMillis()
    if (now - lastAlert > 600000) { //10 minute
      synchronized {
        if (now - lastAlert > 600000) { //10 minutes - double check after getting into the synchronized block
          val serviceDiscovery = injector.instance[ServiceDiscovery]
          serviceDiscovery.changeStatus(ServiceStatus.SICK)
          serviceDiscovery.startSelfCheck()
          serviceDiscovery.forceUpdate()
          lastAlert = System.currentTimeMillis()
        }
      }
    }
  }

  override def onError(request: RequestHeader, ex: Throwable): Future[SimpleResult] = {
    try {
      val errorId: ExternalId[_] = ex match {
        case reported: ReportedException => reported.id
        case _ => injector.instance[AirbrakeNotifier].notify(AirbrakeError.incoming(request, ex, s"Unreported Exception $ex")).id
      }
      System.err.println(s"Play onError handler for ${ex.toString}")
      ex.printStackTrace()
      serviceDiscoveryHandleError()
      val message = if (request.path.startsWith("/internal/")) {
        //todo(eishay) consider use the original ex.getCause instead
        s"${ex.getClass.getSimpleName}:${ex.getMessage.abbreviate(100)}, errorId:${errorId.id}"
      } else {
        errorId.id
      }
      Future.successful(allowCrossOrigin(request, InternalServerError(message)))
    } catch {
      case NonFatal(e) => {
        Logger.error("Error while rendering default error page", e)
        Future.successful(InternalServerError)
      }
    }
  }

  @volatile private var announcedStopping: Boolean = false

  def announceStopping(app: Application): Unit = if(!announcedStopping) synchronized {
    if(!announcedStopping) {//double check on entering sync block
      if (mode == Mode.Prod) {
        try {
          val serviceDiscovery = injector.instance[ServiceDiscovery]
          serviceDiscovery.changeStatus(ServiceStatus.STOPPING)
          println("[announceStopping] let clients and ELB know we're stopping")
          deregisterFromLoadBalancer()
          injector.instance[HealthcheckPlugin].reportStop()
        } catch {
          case t: Throwable => println(s"error announcing service stop via explicit shutdown hook: $t")
        }
      }
      try {
        if (mode == Mode.Prod)
        injector.instance[AppScope].onStop(app)
      } catch {
        case e: Throwable =>
          val errorMessage = "====================== error during onStop ==============================="
          println(errorMessage)
          e.printStackTrace
          log.error(errorMessage, e)
      } finally {
        if (mode == Mode.Prod) {
          println("<<<<<< about to pause and let the system shut down")
          Thread.sleep(3000)
          println("<<<<<< done sleeping, continue with termination")
        }
      }
      announcedStopping = true
    }
  }

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    val serviceDiscovery = injector.instance[ServiceDiscovery]
    announceStopping(app)
    val stopMessage = "<<<<<<<<<< Stopping " + this
    println(stopMessage)
    log.info(stopMessage)
    serviceDiscovery.unRegister()
  }

  private def allowCrossOrigin(request: RequestHeader, result: SimpleResult): SimpleResult = {  // for kifi.com/site dev
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
