package com.keepit.common.healthcheck

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ AlertingActor, UnsupportedActorMessage }
import com.keepit.common.logging.Logging
import com.keepit.common.net._
import com.keepit.model.User

import play.api.libs.json.Json

import akka.actor._

import scala.xml._
import com.keepit.common.zookeeper.ServiceDiscovery

case class AirbrakeErrorNotice(error: AirbrakeError, selfError: Boolean = false)
object AirbrakeDeploymentNotice

private[healthcheck] class AirbrakeNotifierActor @Inject() (
  airbrakeSender: AirbrakeSender,
  healthcheck: HealthcheckPlugin,
  formatter: AirbrakeFormatter,
  pagerDutySender: PagerDutySender)
    extends AlertingActor with Logging {

  def alert(reason: Throwable, message: Option[Any]) = self ! AirbrakeErrorNotice(error(reason, message), true)

  var firstErrorReported = false

  def receive() = {
    case AirbrakeDeploymentNotice =>
      airbrakeSender.sendDeployment(formatter.deploymentMessage)
    case AirbrakeErrorNotice(error, selfError) =>
      try {
        if (error.panic) pagerDutySender.openIncident(error.message.getOrElse(error.exception.toString), error.exception, Some(error.signature.value))
        if (!error.aggregateOnly) {
          val xml = formatter.format(error)
          airbrakeSender.sendError(xml)
          println(xml)
        }
      } catch {
        case e: Throwable =>
          log.error(s"can't format or send error $error")
          pagerDutySender.openIncident("Airbrake Error!", e)
          if (!selfError) throw e
          else {
            System.err.println(s"Airbrake Notifier exception: ${e.toString}")
            e.printStackTrace()
            if (!firstErrorReported) {
              firstErrorReported = true
              val he = healthcheck.addError(AirbrakeError(e, message = Some("Fail to send airbrake message")))
              log.error(s"can't deal with error: $he")
            }
          }
      }
      healthcheck.addError(error)
    case m => self ! AirbrakeErrorNotice(throw new UnsupportedActorMessage(s"unknown message $m"), true)
  }
}

class AirbrakeSender @Inject() (
  httpClient: HttpClient,
  healthcheck: HealthcheckPlugin,
  pagerDutySender: PagerDutySender)
    extends Logging {
  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  var firstErrorReported = false

  val defaultFailureHandler: Request => PartialFunction[Throwable, Unit] = { url =>
    {
      case ex: Exception => if (!firstErrorReported) {
        firstErrorReported = true
        val he = healthcheck.addError(AirbrakeError(ex, message = Some("Fail to send airbrake message")))
        log.error(s"can't deal with error: $he")
        pagerDutySender.openIncident("Airbrake HttpClient Error!", ex)
      }
    }
  }

  def sendDeployment(payload: String): Unit = {
    log.info(s"announcing deployment to airbrake: $payload")
    httpClient.
      withTimeout(CallTimeouts(responseTimeout = Some(60000))).
      withHeaders("Content-type" -> "application/x-www-form-urlencoded").
      postTextFuture(DirectUrl("http://api.airbrake.io/deploys.txt"), payload, httpClient.ignoreFailure)
  }

  def sendError(xml: NodeSeq): Unit = httpClient.
    withTimeout(CallTimeouts(responseTimeout = Some(60000))).
    withHeaders("Content-type" -> "text/xml").
    postXmlFuture(DirectUrl("http://airbrakeapp.com/notifier_api/v2/notices"), xml, defaultFailureHandler) map { res =>
      try {
        val xmlRes = res.xml
        val id = (xmlRes \ "id").head.text
        val url = (xmlRes \ "url").head.text
        log.info(s"sent to airbrake error $id more info at $url: $xml")
        println(s"sent to airbrake error $id more info at $url: $xml")
      } catch {
        case t: Throwable => {
          pagerDutySender.openIncident("Airbrake Response Deserialization Error!", t, moreInfo = Some(res.body.take(1000)))
          throw t
        }
      }
    }
}

class PagerDutySender @Inject() (httpClient: HttpClient, serviceDiscovery: ServiceDiscovery) {

  def openIncident(description: String, exception: Throwable, signature: Option[String] = None, moreInfo: Option[String] = None): Unit = {
    val incidentKey: String = signature.getOrElse(description)
    val service = serviceDiscovery.thisService.name
    val moreInfoMessage: String = moreInfo.getOrElse("See Airbrake/Healthcheck for more.")
    val payload = Json.obj(
      "service_key" -> "7785f2cc14ec44e49ae3bb8186400cc7",
      "event_type" -> "trigger",
      "description" -> s"[$service] $description".take(1000),
      "incident_key" -> incidentKey,
      "details" -> Json.obj(
        "exceptionInfo" -> exception.getMessage,
        "moreInfo" -> moreInfoMessage
      )
    )
    httpClient.postFuture(DirectUrl("https://events.pagerduty.com/generic/2010-04-15/create_event.json"), payload)
  }
}

trait AirbrakeNotifier extends Logging {
  def reportDeployment(): Unit

  def verify(condition: => Boolean, message: => String): Boolean = {
    val pass: Boolean = condition
    if (!pass) {
      log.error(s"[condition fail] $message")
      notify(message)
    }
    pass
  }

  def notify(error: AirbrakeError): AirbrakeError

  def notify(errorException: Throwable): AirbrakeError
  def notify(errorMessage: String, errorException: Throwable): AirbrakeError
  def notify(errorMessage: String): AirbrakeError

  def notify(errorException: Throwable, user: User): AirbrakeError
  def notify(errorMessage: String, errorException: Throwable, user: User): AirbrakeError
  def notify(errorMessage: String, user: User): AirbrakeError

  def panic(error: AirbrakeError): AirbrakeError

  def panic(errorException: Throwable): AirbrakeError
  def panic(errorMessage: String, errorException: Throwable): AirbrakeError
  def panic(errorMessage: String): AirbrakeError

  def panic(errorException: Throwable, user: User): AirbrakeError
  def panic(errorMessage: String, errorException: Throwable, user: User): AirbrakeError
  def panic(errorMessage: String, user: User): AirbrakeError
}

// apiKey is per service type (shoebox, search etc)
class AirbrakeNotifierImpl(actor: ActorInstance[AirbrakeNotifierActor], isCanary: Boolean) extends AirbrakeNotifier with Logging {

  def reportDeployment(): Unit = if (!isCanary) actor.ref ! AirbrakeDeploymentNotice

  def notify(errorException: Throwable): AirbrakeError = notify(AirbrakeError(exception = errorException))

  def notify(errorMessage: String, errorException: Throwable): AirbrakeError = notify(AirbrakeError(message = Some(errorMessage), exception = errorException))

  def notify(errorMessage: String): AirbrakeError = notify(AirbrakeError(message = Some(errorMessage)))

  def notify(error: AirbrakeError): AirbrakeError = {
    if (!isCanary) { // can filter out panic later
      actor.ref ! AirbrakeErrorNotice(error.cleanError)
    }
    log.error(error.toString())
    error
  }

  def notify(errorException: Throwable, user: User): AirbrakeError = notify(AirbrakeError(exception = errorException, userId = user.id, userName = Some(user.fullName)))

  def notify(errorMessage: String, errorException: Throwable, user: User): AirbrakeError = notify(AirbrakeError(message = Some(errorMessage), exception = errorException, userId = user.id, userName = Some(user.fullName)))

  def notify(errorMessage: String, user: User): AirbrakeError = notify(AirbrakeError(message = Some(errorMessage), userId = user.id, userName = Some(user.fullName)))

  def panic(errorException: Throwable): AirbrakeError = panic(AirbrakeError(exception = errorException))

  def panic(errorMessage: String, errorException: Throwable): AirbrakeError = panic(AirbrakeError(message = Some(errorMessage), exception = errorException))

  def panic(errorMessage: String): AirbrakeError = panic(AirbrakeError(message = Some(errorMessage)))

  def panic(errorException: Throwable, user: User): AirbrakeError = panic(AirbrakeError(exception = errorException, userId = user.id, userName = Some(user.fullName)))

  def panic(errorMessage: String, errorException: Throwable, user: User): AirbrakeError = panic(AirbrakeError(message = Some(errorMessage), exception = errorException, userId = user.id, userName = Some(user.fullName)))

  def panic(errorMessage: String, user: User): AirbrakeError = panic(AirbrakeError(message = Some(errorMessage), userId = user.id, userName = Some(user.fullName)))

  def panic(error: AirbrakeError): AirbrakeError = {
    notify(error.copy(panic = true))
  }

}

