package com.keepit.common.logging

import play.api.Logger
import com.google.inject.{ Inject, Singleton }
import com.keepit.common.time._
import com.keepit.common.time.Clock
import org.joda.time.format.DateTimeFormat
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{ Future, future }
import com.keepit.common.concurrent.ExecutionContext

sealed abstract class AccessLogEventType(val name: String, val ignore: Boolean = false)

object Access {
  object DB extends AccessLogEventType("DB")
  object HTTP_IN extends AccessLogEventType("HTTP_IN")
  object HTTP_OUT extends AccessLogEventType("HTTP_OUT")
  object WS_IN extends AccessLogEventType("WS_IN")
  object WS_OUT extends AccessLogEventType("WS_OUT")
  object S3 extends AccessLogEventType("S3")
  object CACHE extends AccessLogEventType("CACHE", true)
}

object AccessLogTimer {
  val NoIntValue: Int = -1
  val NoLongValue: Long = -1L
}

case class AccessLogTimer(eventType: AccessLogEventType, clock: Clock) {
  import AccessLogTimer.NoIntValue

  //since null.asInstanceOf[Int] is forced to be 0 instead of actually null
  private def intOption(i: Int): Option[Int] = if (i == NoIntValue) None else Some(i)

  val startTime = clock.now()

  def laps: Int = {
    val now = clock.now()
    (now.getMillis - startTime.getMillis).toInt
  }

  lazy val duration: Int = laps

  //using null for internal api to make the usage of the call much more friendly without having Some(foo) instead of just foo's
  def done(remoteTime: Int = NoIntValue,
    parsingTime: Option[Int] = None,
    statusCode: Int = NoIntValue,
    result: String = null,
    error: String = null,
    remoteServiceType: String = null,
    remoteUp: String = null,
    remoteLeader: String = null,
    remoteServiceId: String = null,
    remoteHeaders: String = null,
    remoteAddress: String = null,
    query: String = null,
    trackingId: String = null,
    method: String = null,
    currentRequestCount: Int = NoIntValue,
    body: String = null,
    key: String = null,
    space: String = null,
    url: String = null,
    dataSize: Int = NoIntValue,
    requestBody: String = null) = {
    val now = clock.now()
    new AccessLogEvent(
      time = now,
      duration = (now.getMillis - startTime.getMillis).toInt,
      parsingTime = parsingTime,
      eventType = eventType,
      remoteTime = intOption(remoteTime),
      statusCode = intOption(statusCode),
      result = Option(result),
      error = Option(error),
      remoteUp = Option(remoteUp),
      remoteLeader = Option(remoteLeader),
      remoteServiceType = Option(remoteServiceType),
      remoteServiceId = Option(remoteServiceId),
      remoteHeaders = Option(remoteHeaders),
      remoteAddress = Option(remoteAddress),
      query = Option(query),
      trackingId = Option(trackingId),
      method = Option(method),
      currentRequestCount = intOption(currentRequestCount),
      body = Option(body),
      key = Option(key),
      space = Option(space),
      url = Option(url),
      dataSize = intOption(dataSize),
      requestBody = Option(requestBody))
  }
}

class AccessLogEvent(
    val time: DateTime,
    val duration: Int,
    val parsingTime: Option[Int],
    val eventType: AccessLogEventType,
    val remoteTime: Option[Int],
    val statusCode: Option[Int],
    val result: Option[String],
    val error: Option[String],
    val remoteServiceType: Option[String],
    val remoteUp: Option[String],
    val remoteLeader: Option[String],
    val remoteServiceId: Option[String],
    val remoteHeaders: Option[String],
    val remoteAddress: Option[String],
    val query: Option[String],
    val trackingId: Option[String],
    val method: Option[String],
    val currentRequestCount: Option[Int],
    val body: Option[String],
    val key: Option[String],
    val space: Option[String],
    val url: Option[String],
    val dataSize: Option[Int],
    val requestBody: Option[String]) {

  def waitTime: Option[Int] = remoteTime.map(t => duration - t - parsingTime.getOrElse(0))

}

@Singleton
class AccessLog @Inject() (clock: Clock) {

  private val accessLog = Logger("com.keepit.access")
  private val formatter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS")

  def timer(eventType: AccessLogEventType): AccessLogTimer = AccessLogTimer(eventType, clock)

  def add(e: AccessLogEvent): AccessLogEvent = {
    if (!e.eventType.ignore) {
      e.eventType match {
        case Access.CACHE =>
        //todo(eishay) create in memory stats and reporting since we kill the log
        case _ =>
          Future {
            accessLog.info(format(e))
          }(ExecutionContext.singleThread)
      }
    }
    e
  }

  //using the "Labeled Tab-separated Values" format, for more info see http://ltsv.org
  //making sure that values are always at the same order for readability
  def format(e: AccessLogEvent): String = {
    val line: List[Option[String]] =
      Some(s"t:${formatter.print(e.time)}") ::
        Some(s"type:${e.eventType.name}") ::
        Some(s"duration:${e.duration}") ::
        e.currentRequestCount.map("currentRequestCount:" + _) ::
        e.method.map("method:" + _) ::
        e.trackingId.map("trackingId:" + _) ::
        e.remoteAddress.map("remoteAddress:" + _) ::
        e.key.map("key:" + _) ::
        e.space.map("space:" + _) ::
        e.remoteTime.map("remoteTime:" + _) ::
        e.parsingTime.map("parsingTime:" + _) ::
        e.remoteTime.map(t => "waitTime:" + (e.duration - t)) ::
        e.statusCode.map("statusCode:" + _) ::
        e.result.map("result:" + _) ::
        e.remoteServiceType.map("remoteServiceType:" + _) ::
        e.remoteServiceId.map("remoteServiceId:" + _) ::
        e.remoteUp.map("remoteUp:" + _) ::
        e.remoteLeader.map("remoteLeader:" + _) ::
        e.remoteHeaders.map("remoteHeaders:" + _) ::
        e.query.map("query:" + _) ::
        e.url.map("url:" + _) ::
        e.body.map("body:" + _) ::
        e.dataSize.map("dataSize:" + _) ::
        e.error.map("error:" + _) ::
        e.requestBody.map("reqbody:" + _) ::
        Nil
    line.flatten.mkString("\t")
  }
}
