package com.keepit.common.logging

import play.api.Logger
import com.google.inject.{Inject, Singleton}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.zookeeper.ServiceDiscovery
import org.joda.time.format.DateTimeFormat
import scala.concurrent.ExecutionContext.Implicits.global
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
  val NoLongValue: Long = -1L
  val NoIntValue: Int = -1
}

case class AccessLogTimer(eventType: AccessLogEventType, startTime: Long = System.currentTimeMillis) {
  import AccessLogTimer.{NoLongValue, NoIntValue}

  //since null.asInstanceOf[Long] is forced to be 0L instead of actually null
  private def longOption(l: Long): Option[Long] = if(l == NoLongValue) None else Some(l)
  private def intOption(i: Int): Option[Int] = if(i == NoIntValue) None else Some(i)

  //using null for internal api to make the usage of the call much more friendly without having Some(foo) instead of just foo's
  def done(remoteTime: Long = NoLongValue,
          statusCode: Int = NoIntValue,
          success: Option[Boolean] = None,//can't get away without option here
          remoteHost: String = null.asInstanceOf[String],
          targetHost: String = null.asInstanceOf[String],
          remoteService: String = null.asInstanceOf[String],
          remoteServiceId: Long = NoLongValue,
          query: String = null.asInstanceOf[String],
          trackingId: String = null.asInstanceOf[String],
          method: String = null.asInstanceOf[String],
          url: String = null.asInstanceOf[String]) = {
    val now = System.currentTimeMillis
    AccessLogEvent(
      time = now,
      duration = now - startTime,
      eventType = eventType,
      remoteTime = longOption(remoteTime),
      statusCode = intOption(statusCode),
      success = success,
      remoteHost = Option(remoteHost),
      targetHost = Option(targetHost),
      remoteService = Option(remoteService),
      remoteServiceId = longOption(remoteServiceId),
      query = Option(query),
      trackingId = Option(trackingId),
      method = Option(method),
      url = Option(url))
  }
}

case class AccessLogEvent(
  time: Long,
  duration: Long,
  eventType: AccessLogEventType,
  remoteTime: Option[Long],
  statusCode: Option[Int],
  success: Option[Boolean],
  remoteHost: Option[String],
  targetHost: Option[String],
  remoteService: Option[String],
  remoteServiceId: Option[Long],
  query: Option[String],
  trackingId: Option[String],
  method: Option[String],
  url: Option[String])

@Singleton
class AccessLog {

  private val accessLog = Logger("com.keepit.access")
  private val formatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS")

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
      e.remoteTime.map("remoteTime:" + _) ::
      e.remoteTime.map(t => "waitTime:" + (e.duration - t)) ::
      e.statusCode.map("statusCode:" + _) ::
      e.success.map("success:" + _) ::
      e.remoteHost.map("remoteHost:" + _) ::
      e.targetHost.map("targetHost:" + _) ::
      e.remoteService.map("remoteService:" + _) ::
      e.remoteServiceId.map("remoteServiceId:" + _) ::
      e.query.map("query:" + _) ::
      e.trackingId.map("trackingId:" + _) ::
      e.url.map("url:" + _) ::
      Nil
    line.flatten.mkString("\t")
  }

}
