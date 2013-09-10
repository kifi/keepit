package com.keepit.common.healthcheck

import com.google.inject.Inject
import com.google.inject.ImplementedBy
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.logging.Logging
import com.keepit.common.net._

import akka.actor._

import java.io._
import java.net._
import scala.xml._

case class AirbrakeNotice(xml: NodeSeq)

private[healthcheck] class AirbrakeNotifierActor @Inject() (
    healthcheckPlugin: HealthcheckPlugin,
    airbrakeSender: AirbrakeSender)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case AirbrakeNotice(xml) => airbrakeSender.send(xml)
    case m => throw new Exception(s"unknown message $m")
  }
}

class AirbrakeSender @Inject() (httpClient: HttpClient) extends Logging {
  import scala.concurrent.ExecutionContext.Implicits.global

  def send(xml: NodeSeq) = httpClient.
    withHeaders("Content-type" -> "text/xml").
    postXmlFuture("http://airbrakeapp.com/notifier_api/v2/notices", xml) map { res =>
      val xml = res.xml
      val id = (xml \ "id").head.text
      val url = (xml \ "url").head.text
      log.info(s"sent to airbreak error $id more info at $url")
      println(s"sent to airbreak error $id more info at $url")
    }
}

// apiKey is per service type (showbox, search etc)
class AirbrakeNotifier @Inject() (
  apiKey: String,
  actor: ActorInstance[AirbrakeNotifierActor]) {

  def notifyError(error: AirbrakeError) = actor.ref ! AirbrakeNotice(format(error))

  private def formatStacktrace(traceElements: Array[StackTraceElement]) =
    traceElements.flatMap(e => {
      <line method={e.getMethodName} file={e.getFileName} number={e.getLineNumber.toString}/>
    })

  private def formatParams(params: Map[String,List[String]]) =
    <params>{params.flatMap(e => {
        <var key={e._1}>{e._2.mkString(" ")}</var>
    })}</params>

  //todo(eishay): add component and session
  private def noticeRequest(url: String, params: Map[String, List[String]], method: Option[String]) =
    <request>
      <url>{url}</url>
      { formatParams(params) }
      <component/>
      { method.map(m => <action>m</action>).getOrElse(<action/>) }
    </request>

  private def noticeError(error: Throwable) =
    <error>
      <class>{error.getClass.getName}</class>
      <message>{error.getMessage}</message>
      <backtrace>
        { formatStacktrace(error.getStackTrace) }
      </backtrace>
    </error>

  private def noticeEntities(error: AirbrakeError) =
    (Some(noticeError(error.exception)) :: error.url.map{u => noticeRequest(u, error.params, error.method)} :: Nil).flatten

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
        <environment-name>production</environment-name>
      </server-environment>
    </notice>
}

