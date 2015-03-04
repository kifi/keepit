package com.keepit.common.service

import com.keepit.common.akka.SafeFuture
import com.keepit.common.amazon.AmazonInstanceInfo

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try, Random }

import com.keepit.common.concurrent.RetryFuture
import com.keepit.common.healthcheck.{ StackTrace, AirbrakeError, AirbrakeNotifier }
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ CallTimeouts, ClientResponse, HttpClient, HttpUri }
import com.keepit.common.routes._
import com.keepit.common.zookeeper.{ ServiceCluster, ServiceInstance }
import com.keepit.common.core._
import com.keepit.common.strings._
import com.keepit.common.concurrent.ReactiveLock

import java.net.ConnectException

import play.api.libs.json.{ JsNull, JsValue }
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.routes.ServiceRoute
import scala.collection.mutable.{ SynchronizedSet, HashSet, Set => MutableSet }

class ServiceNotAvailableException(serviceType: ServiceType)
  extends Exception(s"Service of type ${serviceType.name} is not available")

object ServiceClient {
  val MaxUrlLength = 1000
  sealed trait Register[V] extends MutableSet[V]
  final class HashSetRegister[V] extends HashSet[V] with SynchronizedSet[V] with Register[V]
  val register = new HashSetRegister[ServiceClient]
}

class ServiceUri(val serviceInstance: ServiceInstance, protocol: String, port: Int, path: String)
    extends HttpUri {
  override val serviceInstanceOpt = Some(serviceInstance)
  override def summary: String = s"$service:${path.abbreviate(100)}"
  override def service: String = s"${serviceInstance.remoteService.serviceType.shortName}${serviceInstance.id.id.toString}"
  lazy val url: String = s"$protocol://${serviceInstance.instanceInfo.localIp.ip}:${port}$path"
}

case class ServiceResponse(uri: ServiceUri, response: ClientResponse)

trait RoutingStrategy {
  def nextInstance: ServiceInstance
}

trait ServiceClient extends CommonServiceUtilities with Logging {
  protected def httpClient: HttpClient

  def serviceCluster: ServiceCluster
  val airbrakeNotifier: AirbrakeNotifier

  val roundRobin = new RoutingStrategy {
    def nextInstance = serviceCluster.nextService().getOrElse(throw new ServiceNotAvailableException(serviceCluster.serviceType))
  }

  val leaderPriority = new RoutingStrategy {
    def nextInstance = serviceCluster.leader.filter(_.isUp) orElse serviceCluster.nextService() getOrElse (throw new ServiceNotAvailableException(serviceCluster.serviceType))
  }

  val offlinePriority = new RoutingStrategy {
    def nextInstance = serviceCluster.nextService(Some(ServiceStatus.OFFLINE))
      .getOrElse(leaderPriority.nextInstance)
  }

  private def nextInstance(): ServiceInstance =
    serviceCluster.nextService().getOrElse(throw new ServiceNotAvailableException(serviceCluster.serviceType))

  val protocol: String = "http"
  val port: Int = 9000

  protected def serviceUri(path: String, router: RoutingStrategy) = new ServiceUri(router.nextInstance, protocol, port, path)

  protected def url(path: String): ServiceUri = new ServiceUri(nextInstance(), protocol, port, path)

  protected def urls(path: String, includeUnavailable: Boolean = false, includeSelf: Boolean = false): Seq[ServiceUri] = {
    val relevantInstances = (if (includeUnavailable) serviceCluster.allMembers else serviceCluster.allServices).filter(includeSelf || !_.thisInstance)
    relevantInstances.map(new ServiceUri(_, protocol, port, path)) tap { uris =>
      if (uris.length == 0) {
        log.warn("Broadcasting/Teeing to no-one!")
      }
    }
  }

  protected def call(call: ServiceRoute, body: JsValue = JsNull, attempts: Int = 2, callTimeouts: CallTimeouts = CallTimeouts.NoTimeouts, routingStrategy: RoutingStrategy = roundRobin): Future[ClientResponse] = {
    val tracer = new StackTrace()
    val respFuture = RetryFuture(attempts, { case t: ConnectException => serviceCluster.refresh(); true }) {
      callUrl(call, serviceUri(call.url, routingStrategy), body, ignoreFailure = true, callTimeouts = callTimeouts)
    }
    respFuture.onSuccess {
      case res: ClientResponse => if (!res.isUp) {
        serviceCluster.refresh()
        res.request.httpUri.serviceInstanceOpt.map(_.reportServiceUnavailable())
      }
    }
    respFuture.onFailure {
      case ex: Throwable =>
        val stringBody = body.toString()
        val withTrace = tracer.withCause(ex)
        airbrakeNotifier.notify(AirbrakeError(
          exception = withTrace,
          message = Some(
            s"can't call [${call.path}] " +
              s"with body: ${stringBody.abbreviate(30)} (${stringBody.size} chars), " +
              s"params: [${call.params.map(_.toString()).mkString(",")}]"),
          method = Some(call.method.toString),
          url = Some(call.path)))
        //also dumping the full thing to the log in case we want to dig into the error details
        log.error(s"can't call [${call.path}] with body: $stringBody , params: [${call.params.map(_.toString()).mkString(",")}] : ${ex.getMessage}", withTrace)
    }
    respFuture
  }

  protected def callUrl(call: ServiceRoute, httpUri: HttpUri, body: JsValue, ignoreFailure: Boolean = false, callTimeouts: CallTimeouts = CallTimeouts.NoTimeouts): Future[ClientResponse] = {
    val clientResponseFuture = {
      val url = httpUri.url
      if (url.length > ServiceClient.MaxUrlLength) {
        airbrakeNotifier.notify(AirbrakeError(
          message = Some(s"Request URI length ${url.length} > ${ServiceClient.MaxUrlLength}: $url"),
          method = Some(call.method.toString),
          url = Some(s"$url")))
      }
      if (ignoreFailure) {
        call match {
          case c @ ServiceRoute(GET, _, _*) => httpClient.withTimeout(callTimeouts).getFuture(httpUri, httpClient.ignoreFailure)
          case c @ ServiceRoute(POST, _, _*) => httpClient.withTimeout(callTimeouts).postFuture(httpUri, body, httpClient.ignoreFailure)
        }
      } else {
        call match {
          case c @ ServiceRoute(GET, _, _*) => httpClient.withTimeout(callTimeouts).getFuture(httpUri)
          case c @ ServiceRoute(POST, _, _*) => httpClient.withTimeout(callTimeouts).postFuture(httpUri, body)
        }
      }
    }
    new SafeFuture[ClientResponse](clientResponseFuture, Some(s"Handling service call: $call (body: $body)"))
  }

  private def logBroadcast(url: ServiceUri, body: JsValue = JsNull): Unit = {
    log.info(s"[broadcast] Sending to $url: ${body.toString.take(120)}")
  }

  protected def broadcastWithUrls(call: ServiceRoute, body: JsValue = JsNull): Seq[Future[ServiceResponse]] = {
    urls(call.url) map { url =>
      logBroadcast(url, body)
      callUrl(call, url, body) map { ServiceResponse(url, _) }
    }
  }

  protected def broadcast(call: ServiceRoute, body: JsValue = JsNull, includeUnavailable: Boolean = false, includeSelf: Boolean = false): Map[AmazonInstanceInfo, Future[ClientResponse]] = {
    urls(call.url, includeUnavailable, includeSelf) map { url =>
      logBroadcast(url, body)
      url.serviceInstance.instanceInfo -> callUrl(call, url, body)
    }
  }.toMap

  protected def collectResponses(calls: Map[AmazonInstanceInfo, Future[ClientResponse]]): Future[Map[AmazonInstanceInfo, Try[ClientResponse]]] = {
    val safeCalls = calls.mapValues { call =>
      call.map(Success(_)).recover {
        case error: Throwable =>
          airbrakeNotifier.notify(s"Failed service call was ignored.", error)
          Failure(error)
      }
    }
    val (instances, futureResponses) = safeCalls.toSeq.unzip
    Future.sequence(futureResponses).map { responses => (instances zip responses).toMap }
  }

  protected def callLeader(call: ServiceRoute, body: JsValue = JsNull, ignoreFailure: Boolean = false, callTimeouts: CallTimeouts = CallTimeouts.NoTimeouts) = {
    serviceCluster.leader match {
      case Some(clusterLeader) =>
        callUrl(call, new ServiceUri(clusterLeader, protocol, port, call.url), body, ignoreFailure, callTimeouts)
      case None =>
        log.info("[callLeader] I don't know any leaders, so calling everyone.")
        Future.sequence(urls(call.url).map { url =>
          callUrl(call, url, body, ignoreFailure, callTimeouts)
        }).map { res =>
          res.last
        }
    }
  }

  protected def tee(call: ServiceRoute, body: JsValue = JsNull, teegree: Int = 2): Future[ClientResponse] = {
    val futures = Random.shuffle(urls(call.url)).take(teegree).map(callUrl(call, _, body)) //need to shuffle
    Future.firstCompletedOf(futures)
  }

  ServiceClient.register.add(this)
}

trait CommonServiceUtilities { self: ServiceClient =>
  def removeAllFromLocalCaches(prefix: Option[String]): Future[Seq[ClientResponse]] = Future.sequence(broadcast(Common.internal.removeAllFromLocalCache(prefix)).values.toSeq)
}

trait ThrottledServiceClient extends ServiceClient {
  val maxParallelism: Int = 4
  val maxQueue: Option[Int] = Some(maxParallelism * 42)
  val limiter = new ReactiveLock(maxParallelism, maxQueue)
  override protected def callUrl(call: ServiceRoute, httpUri: HttpUri, body: JsValue, ignoreFailure: Boolean = false, callTimeouts: CallTimeouts = CallTimeouts.NoTimeouts): Future[ClientResponse] = limiter.withLockFuture {
    super.callUrl(call, httpUri, body, ignoreFailure, callTimeouts)
  }
}
