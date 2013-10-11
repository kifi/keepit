package com.keepit.common.logging

import play.api.Logger
import com.google.inject.{Inject, Singleton}
import com.keepit.common.healthcheck._
import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time.Clock
import com.keepit.common.zookeeper.ServiceDiscovery
import org.joda.time.format.DateTimeFormat
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.future

sealed abstract class AccessLogEventType(val name: String)

object Access {
  object DB extends AccessLogEventType("DB")
  object HTTP_IN extends AccessLogEventType("HTTP_IN")
  object HTTP_OUT extends AccessLogEventType("HTTP_OUT")
  object WS_IN extends AccessLogEventType("WS_IN")
  object WS_OUT extends AccessLogEventType("WS_OUT")
  object S3 extends AccessLogEventType("S3")
  object CACHE extends AccessLogEventType("CACHE")
}

object AccessLogTimer {
  val NoIntValue: Int = -1
}

case class AccessLogTimer(eventType: AccessLogEventType, clock: Clock) {
  import AccessLogTimer.NoIntValue

  //since null.asInstanceOf[Int] is forced to be 0 instead of actually null
  private def intOption(i: Int): Option[Int] = if(i == NoIntValue) None else Some(i)

  val startTime = clock.now()

  //using null for internal api to make the usage of the call much more friendly without having Some(foo) instead of just foo's
  def done(remoteTime: Int = NoIntValue,
          statusCode: Int = NoIntValue,
          result: String = null,
          remoteHost: String = null,
          targetHost: String = null,
          remoteService: String = null,
          remoteServiceId: Int = NoIntValue,
          query: String = null,
          trackingId: String = null,
          method: String = null,
          body: String = null,
          key: String = null,
          space: String = null,
          url: String = null) = {
    val now = clock.now()
    AccessLogEvent(
      time = now,
      duration = (now.getMillis - startTime.getMillis).toInt,
      eventType = eventType,
      remoteTime = intOption(remoteTime),
      statusCode = intOption(statusCode),
      result = Option(result),
      remoteHost = Option(remoteHost),
      targetHost = Option(targetHost),
      remoteService = Option(remoteService),
      remoteServiceId = intOption(remoteServiceId),
      query = Option(query),
      trackingId = Option(trackingId),
      method = Option(method),
      body = Option(body),
      key = Option(key),
      space = Option(space),
      url = Option(url))
  }
}

case class AccessLogEvent(
  time: DateTime,
  duration: Int,
  eventType: AccessLogEventType,
  remoteTime: Option[Int],
  statusCode: Option[Int],
  result: Option[String],
  remoteHost: Option[String],
  targetHost: Option[String],
  remoteService: Option[String],
  remoteServiceId: Option[Int],
  query: Option[String],
  trackingId: Option[String],
  method: Option[String],
  body: Option[String],
  key: Option[String],
  space: Option[String],
  url: Option[String]) {

  def waitTime: Option[Int] = remoteTime.map(t => duration - t)

}

@Singleton
class AccessLog @Inject() (clock: Clock) {

  private val accessLog = Logger("com.keepit.access")
  private val formatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS")

  def timer(eventType: AccessLogEventType): AccessLogTimer = AccessLogTimer(eventType, clock)

  def add(e: AccessLogEvent): AccessLogEvent = {
    future {accessLog.info(format(e)) }
    e
  }

  //using the "Labeled Tab-separated Values" format, for more info see http://ltsv.org
  //making sure that values are always at the same order for readability
  def format(e: AccessLogEvent): String = {
    val line: List[Option[String]] =
      Some(s"t:${formatter.print(e.time)}") ::
      Some(s"type:${e.eventType.name}") ::
      Some(s"duration:${e.duration}") ::
      e.method.map("method:" + _) ::
      e.trackingId.map("trackingId:" + _) ::
      e.key.map("key:" + _) ::
      e.space.map("space:" + _) ::
      e.remoteTime.map("remoteTime:" + _) ::
      e.remoteTime.map(t => "waitTime:" + (e.duration - t)) ::
      e.statusCode.map("statusCode:" + _) ::
      e.result.map("result:" + _) ::
      e.remoteHost.map("remoteHost:" + _) ::
      e.targetHost.map("targetHost:" + _) ::
      e.remoteService.map("remoteService:" + _) ::
      e.remoteServiceId.map("remoteServiceId:" + _) ::
      e.query.map("query:" + _) ::
      e.url.map("url:" + _) ::
      e.body.map("body:" + _) ::
      Nil
    line.flatten.mkString("\t")
  }
}
