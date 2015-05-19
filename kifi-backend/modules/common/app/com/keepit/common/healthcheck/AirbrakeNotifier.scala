package com.keepit.common.healthcheck

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ AlertingActor, UnsupportedActorMessage }
import com.keepit.common.logging.Logging
import com.keepit.common.net._
import com.keepit.common.service.FortyTwoServices
import com.keepit.model.{ User, NotificationCategory }
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail }

import scala.concurrent.ExecutionContext

import play.api.libs.json.{ JsValue, JsObject, Json }

import akka.actor._

import scala.xml._
import com.keepit.common.zookeeper.ServiceDiscovery

case class AirbrakeErrorNotice(error: AirbrakeError, selfError: Boolean = false)
object AirbrakeDeploymentNotice

private[healthcheck] class AirbrakeNotifierActor @Inject() (
  airbrakeSender: AirbrakeSender,
  healthcheck: HealthcheckPlugin,
  formatter: JsonAirbrakeFormatter,
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
          val json: JsValue = formatter.format(error)
          airbrakeSender.sendError(json)
          val toLog = error.message.getOrElse(error.exception.toString)
          println(s"[airbrake] $toLog")
          log.error(s"[airbrake] $toLog")
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
  pagerDutySender: PagerDutySender,
  service: FortyTwoServices,
  implicit val defaultContext: ExecutionContext,
  systemAdminMailSender: SystemAdminMailSender)
    extends Logging {

  // Where are these injected from?
  val apiKey = "701215c3c9029a56df52250ce2c5750f"
  val projectId = "91268"

  var firstErrorReported = false

  val defaultFailureHandler: Request => PartialFunction[Throwable, Unit] = { url =>
    {
      case ex: Exception => if (!firstErrorReported) {
        firstErrorReported = true
        val he = healthcheck.addError(AirbrakeError(ex, message = Some("Fail to send airbrake message")))
        log.error(s"can't deal with error: $he")
        if (ex.getMessage.contains("Project is rate limited")) {
          pagerDutySender.openIncident(s"[${service.currentService}] Airbrake over Rate Limit!", ex)
        } else {
          systemAdminMailSender.sendMail(ElectronicMail(from = SystemEmailAddress.ENG,
            to = Seq(SystemEmailAddress.ENG),
            category = NotificationCategory.System.HEALTHCHECK,
            subject = s"[${service.currentService}] [WARNING] Could not send airbrake error (Airbrake down?)",
            htmlBody = ex.getMessage))
        }
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

  def sendError(json: JsValue): Unit = {
    val futureResult = httpClient
      .withHeaders("Content-Type" -> "application/json")
      .withTimeout(CallTimeouts(responseTimeout = Some(60000)))
      .postFuture(DirectUrl(s"https://airbrake.io/api/v3/projects/$projectId/notices?key=$apiKey"), json, defaultFailureHandler)
    futureResult.onSuccess {
      case res: ClientResponse =>
        try {
          val jsonRes = res.json
          val id = (jsonRes \ "id").as[String]
          val url = (jsonRes \ "url").as[String]
          log.info(s"sent airbrake error $id, more info at $url: $json")
          println(s"sent airbrake error $id, more info at $url: $json")
        } catch {
          case t: Throwable => {
            pagerDutySender.openIncident("Airbrake Response Deserialization Error!", t, moreInfo = Some(res.body.take(1000)))
            throw t
          }
        }
    }

    futureResult.onFailure {
      case exception =>
        log.info(s"error sending airbrake json: $json", exception)
        exception.printStackTrace()
        println(s"error sending airbrake json: $json")
    }
  }

  def sendError(xml: NodeSeq): Unit = {
    val futureResult = httpClient.
      withTimeout(CallTimeouts(responseTimeout = Some(60000))).
      withHeaders("Content-type" -> "text/xml").
      postXmlFuture(DirectUrl("http://airbrakeapp.com/notifier_api/v2/notices"), xml, defaultFailureHandler)
    futureResult.onSuccess {
      case res =>
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
    futureResult.onFailure {
      case exception =>
        log.info(s"error sending airbrake xml: $xml", exception)
        exception.printStackTrace()
        println(s"error sending airbrake xml: $xml")
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

