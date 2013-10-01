package com.keepit.common.logging

import play.api.Logger
import com.google.inject.{Inject, Singleton}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.zookeeper.ServiceDiscovery
import org.joda.time.format.DateTimeFormat

sealed trait AccessLogEventType { val name: String }

object Access {
  object DB extends AccessLogEventType { val name = "DB" }
  object HTTP_IN extends AccessLogEventType { val name = "HTTP_IN" }
  object HTTP_OUT extends AccessLogEventType { val name = "HTTP_OUT" }
  object WS_IN extends AccessLogEventType { val name = "WS_IN" }
  object WS_OUT extends AccessLogEventType { val name = "WS_OUT" }
  object S3 extends AccessLogEventType { val name = "S3" }
  object CACHE extends AccessLogEventType { val name = "CACHE" }
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
          returnCode: Int = NoIntValue,
          success: Option[Boolean] = None,//can't get away without option here
          remoteHost: String = null.asInstanceOf[String],
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
      returnCode = intOption(returnCode),
      success = success,
      remoteHost = Option(remoteHost),
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
  returnCode: Option[Int],
  success: Option[Boolean],
  remoteHost: Option[String],
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

  def add(e: AccessLogEvent) = accessLog.info(format(e))

  def format(e: AccessLogEvent): String = {
    val line: List[Option[String]] =
      Some(s"t:${formatter.print(e.time)}") ::
      Some(s"duration:${e.duration}") ::
      Some(s"type:${e.eventType.name}") ::
      e.remoteTime.map("remoteTime:" + _) ::
      e.remoteTime.map(t => "waitTime:" + (e.duration - t)) ::
      e.returnCode.map("returnCode:" + _) ::
      e.success.map("success:" + _) ::
      e.remoteHost.map("remoteHost:" + _) ::
      e.remoteService.map("remoteService:" + _) ::
      e.remoteServiceId.map("remoteServiceId:" + _) ::
      e.query.map("query:" + _) ::
      e.trackingId.map("trackingId:" + _) ::
      e.method.map("method:" + _) ::
      e.url.map("url:" + _) ::
      Nil
    line.flatten.mkString("\t")
  }

}
