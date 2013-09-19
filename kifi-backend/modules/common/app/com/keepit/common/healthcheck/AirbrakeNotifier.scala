package com.keepit.common.healthcheck

import com.keepit.common.db.ExternalId
import com.google.inject.Inject
import com.google.inject.ImplementedBy
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.AlertingActor
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.logging.Logging
import com.keepit.common.net._

import play.api.Mode.Mode

import akka.actor._

import java.io._
import java.net._
import scala.xml._

import play.api.mvc._

case class AirbrakeNotice(xml: NodeSeq)

private[healthcheck] class AirbrakeNotifierActor @Inject() (
    airbrakeSender: AirbrakeSender,
    formatter: AirbrakeFormatter)
  extends AlertingActor with Logging {

  def alert(reason: Throwable, message: Option[Any]) = self ! AirbrakeNotice(formatter.format(error(reason, message)))

  def receive() = {
    case AirbrakeNotice(xml) => airbrakeSender.send(xml); println(xml)
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

class AirbrakeFormatter(val apiKey: String, val playMode: Mode, service: FortyTwoServices) {

  private def formatStacktrace(traceElements: Array[StackTraceElement]) =
    traceElements.filter(e => !e.getFileName.contains("Airbrake")).map(e => {
      <line method={e.getMethodName} file={e.getFileName} number={e.getLineNumber.toString}/>
    })

  private def formatParams(params: Map[String,Seq[String]]) = params.isEmpty match {
    case false =>
      (<params>{params.flatMap(e => {
          <var key={e._1}>{e._2.mkString(" ")}</var>
      })}</params>)::Nil
    case true => Nil
  }

  private def formatHeaders(params: Map[String,Seq[String]], id: ExternalId[AirbrakeError]) = params.isEmpty match {
    case false =>
      (<session>
        <var key="InternalErrorId">{id.id}</var>
        {params.flatMap(e => {
          <var key={e._1}>{e._2.mkString(" ")}</var>
        }
      )}</session>)::Nil
    case true => Nil
  }

  //todo(eishay): add component and session
  private def noticeRequest(url: String, params: Map[String, Seq[String]], method: Option[String], headers: Map[String, Seq[String]], id: ExternalId[AirbrakeError]) =
    <request>
      <url>{url}</url>
      <component/>
      { formatParams(params) }
      { method.map(m => <action>{m}</action>).getOrElse(<action/>) }
      { formatHeaders(headers.toMap, id) }
    </request>

  private def noticeError(error: Throwable, message: Option[String]) =
    <error>
      <class>{error.getClass.getName}</class>
      <message>{ message.getOrElse("") + error.toString() }</message>
      <backtrace>
        { formatStacktrace(error.getStackTrace) }
      </backtrace>
    </error>

  private def noticeEntities(error: AirbrakeError) =
    (Some(noticeError(error.exception, error.message)) :: error.url.map{u => noticeRequest(u, error.params, error.method, error.headers, error.id)} :: Nil).flatten

  //http://airbrake.io/airbrake_2_3.xsd
  private[healthcheck] def format(error: AirbrakeError) =
    <notice version="2.3">
      <api-key>{apiKey}</api-key>
      <notifier>
        <name>S42</name>
        <version>0.0.1</version>
        <url>http://admin.kifi.com/admin</url>
      </notifier>
      { noticeEntities(error) }
      <server-environment>
        <project-root>{service.currentService}</project-root>
        <environment-name>{playMode.toString}</environment-name>
        <app-version>{service.currentVersion}</app-version>
        <hostname>{service.baseUrl}</hostname>
      </server-environment>
    </notice>
}

trait AirbrakeNotifier {
  def notify(error: AirbrakeError): AirbrakeError
}

// apiKey is per service type (showbox, search etc)
class AirbrakeNotifierImpl (
  actor: ActorInstance[AirbrakeNotifierActor],
  formatter: AirbrakeFormatter) extends AirbrakeNotifier with Logging {

  def notify(error: AirbrakeError): AirbrakeError = {
    actor.ref ! AirbrakeNotice(formatter.format(error))
    log.error(error.toString())
    error
  }
}

