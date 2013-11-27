package com.keepit.common.healthcheck

import com.keepit.common.db.ExternalId
import com.google.inject.Inject
import com.google.inject.ImplementedBy
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{AlertingActor, UnsupportedActorMessage}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.logging.Logging
import com.keepit.common.net._

import play.api.Mode._

import akka.actor._

import java.io._
import java.net._
import scala.xml._

case class AirbrakeErrorNotice(error: AirbrakeError, selfError: Boolean = false)
object AirbrakeDeploymentNotice

private[healthcheck] class AirbrakeNotifierActor @Inject() (
    airbrakeSender: AirbrakeSender,
    healthcheck: HealthcheckPlugin,
    formatter: AirbrakeFormatter)
  extends AlertingActor with Logging {

  def alert(reason: Throwable, message: Option[Any]) = self ! AirbrakeErrorNotice(error(reason, message), true)

  var firstErrorReported = false

  def receive() = {
    case AirbrakeDeploymentNotice =>
      airbrakeSender.sendDeployment(formatter.deploymentMessage)
    case AirbrakeErrorNotice(error, selfError) =>
      try {
        val xml = formatter.format(error)
        airbrakeSender.sendError(xml)
        println(xml)
      } catch {
        case e: Throwable =>
          log.error(s"can't format or send error $error")
          if (!selfError) throw e
          else {
            System.err.println(s"Airbrake Notifier exception: ${e.toString}")
            e.printStackTrace
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
  healthcheck: HealthcheckPlugin)
    extends Logging {
  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  var firstErrorReported = false

  val defaultFailureHandler: Request => PartialFunction[Throwable, Unit] = { url =>
    {
      case ex: Exception => if (!firstErrorReported) {
        firstErrorReported = true
        val he = healthcheck.addError(AirbrakeError(ex, message = Some("Fail to send airbrake message")))
        log.error(s"can't deal with error: $he")
      }
    }
  }

  def sendDeployment(payload: String): Unit = {
    log.info(s"announcing deployment to airbrake: $payload")
    httpClient.
      withTimeout(60000).
      withHeaders("Content-type" -> "application/x-www-form-urlencoded").
      postTextFuture(DirectUrl("http://api.airbrake.io/deploys.txt"), payload, httpClient.ignoreFailure)
  }

  def sendError(xml: NodeSeq): Unit = httpClient.
    withTimeout(60000).
    withHeaders("Content-type" -> "text/xml").
    postXmlFuture(DirectUrl("http://airbrakeapp.com/notifier_api/v2/notices"), xml, defaultFailureHandler) map { res =>
      val xmlRes = res.xml
      val id = (xmlRes \ "id").head.text
      val url = (xmlRes \ "url").head.text
      log.info(s"sent to airbreak error $id more info at $url: $xml")
      println(s"sent to airbreak error $id more info at $url: $xml")
    }
}

trait AirbrakeNotifier {
  def reportDeployment(): Unit
  def notify(error: AirbrakeError): AirbrakeError
  def notify(errorException: Throwable): AirbrakeError
  def notify(errorMessage: String, errorException: Throwable): AirbrakeError
  def notify(errorMessage: String): AirbrakeError
}

// apiKey is per service type (showbox, search etc)
class AirbrakeNotifierImpl (
  actor: ActorInstance[AirbrakeNotifierActor]) extends AirbrakeNotifier with Logging {

  def reportDeployment(): Unit = actor.ref ! AirbrakeDeploymentNotice

  def notify(errorException: Throwable): AirbrakeError = notify(AirbrakeError(exception = errorException))

  def notify(errorMessage: String, errorException: Throwable): AirbrakeError = notify(AirbrakeError(message = Some(errorMessage), exception = errorException))

  def notify(errorMessage: String): AirbrakeError = notify(AirbrakeError(message = Some(errorMessage)))

  def notify(error: AirbrakeError): AirbrakeError = {
    actor.ref ! AirbrakeErrorNotice(error.cleanError)
    log.error(error.toString())
    error
  }
}

