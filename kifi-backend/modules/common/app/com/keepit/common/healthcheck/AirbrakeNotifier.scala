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

import play.api.mvc._

case class AirbrakeNotice(error: AirbrakeError, selfError: Boolean = false)

private[healthcheck] class AirbrakeNotifierActor @Inject() (
    airbrakeSender: AirbrakeSender,
    healthcheck: HealthcheckPlugin,
    formatter: AirbrakeFormatter)
  extends AlertingActor with Logging {

  def alert(reason: Throwable, message: Option[Any]) = self ! AirbrakeNotice(error(reason, message), true)

  var firstErrorReported = false

  def receive() = {
    case AirbrakeNotice(error, selfError) => {
      try {
        val xml = formatter.format(error)
        airbrakeSender.send(xml);
        println(xml)
      } catch {
        case e: Throwable =>
          log.error(s"can't format or send error $error")
          if (!selfError) throw e
          else {
            e.printStackTrace
            if (!firstErrorReported) {
              firstErrorReported = true
              val he = healthcheck.addError(HealthcheckError(Some(e), None, None, Healthcheck.INTERNAL, Some(e.getMessage)))
              log.error(s"can't deal with error: $he")
            }
          }
      }
    }
    case m => self ! AirbrakeNotice(throw new UnsupportedActorMessage(s"unknown message $m"), true)
  }
}

class AirbrakeSender @Inject() (
  httpClient: HttpClient,
  healthcheck: HealthcheckPlugin)
    extends Logging {
  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  var firstErrorReported = false

  val defaultOnFailure: String => PartialFunction[Throwable, Unit] = { url =>
    {
      case ex: Exception => if (!firstErrorReported) {
        firstErrorReported = true
        val he = healthcheck.addError(HealthcheckError(Some(ex), None, None, Healthcheck.INTERNAL, Some(ex.getMessage)))
        log.error(s"can't deal with error: $he")
      }
    }
  }

  def send(xml: NodeSeq) = httpClient.
    withHeaders("Content-type" -> "text/xml").
    postXmlFuture("http://airbrakeapp.com/notifier_api/v2/notices", xml, defaultOnFailure) map { res =>
      val xmlRes = res.xml
      val id = (xmlRes \ "id").head.text
      val url = (xmlRes \ "url").head.text
      log.info(s"sent to airbreak error $id more info at $url: $xml")
      println(s"sent to airbreak error $id more info at $url: $xml")
    }
}

trait AirbrakeNotifier {
  def notify(error: AirbrakeError): AirbrakeError
}

// apiKey is per service type (showbox, search etc)
class AirbrakeNotifierImpl (
  actor: ActorInstance[AirbrakeNotifierActor]) extends AirbrakeNotifier with Logging {

  def notify(error: AirbrakeError): AirbrakeError = {
    actor.ref ! AirbrakeNotice(error)
    log.error(error.toString())
    error
  }
}

