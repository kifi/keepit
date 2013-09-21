package com.keepit.common.healthcheck

import com.keepit.common.db.ExternalId
import com.google.inject.Inject
import com.google.inject.ImplementedBy
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.AlertingActor
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.logging.Logging
import com.keepit.common.net._

import play.api.Mode._

import akka.actor._

import java.io._
import java.net._
import scala.xml._

import play.api.mvc._

case class AirbrakeNotice(error: AirbrakeError)

private[healthcheck] class AirbrakeNotifierActor @Inject() (
    airbrakeSender: AirbrakeSender,
    formatter: AirbrakeFormatter)
  extends AlertingActor with Logging {

  def alert(reason: Throwable, message: Option[Any]) = self ! AirbrakeNotice(error(reason, message))

  def receive() = {
    case AirbrakeNotice(error) => {
      try {
        val xml = formatter.format(error)
        airbrakeSender.send(xml);
        println(xml)
      } catch {
        case e: Throwable =>
          log.error(s"can't format or send error $error")
          throw e
      }
    }
    case m => throw new Exception(s"unknown message $m")
  }
}

class AirbrakeSender @Inject() (httpClient: HttpClient) extends Logging {
  import scala.concurrent.ExecutionContext.Implicits.global

  def send(xml: NodeSeq) = httpClient.
    withHeaders("Content-type" -> "text/xml").
    postXmlFuture("http://airbrakeapp.com/notifier_api/v2/notices", xml) map { res =>
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

