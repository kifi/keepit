package com.keepit.common.controller

import com.google.inject.{ Provider, Inject, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.amazon.MyInstanceInfo
import com.keepit.common.logging.Logging
import play.api.mvc.RequestHeader
import scala.collection.JavaConversions._
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ConcurrentHashMap

//todo(eishay): add alerts/stats on the longest outstanding request and/or average time using the value of the currentRequests map
@Singleton
class MidFlightRequests @Inject() (
    airbrake: Provider[AirbrakeNotifier],
    myInstanceInfo: Provider[MyInstanceInfo]) extends Logging {

  private[this] val currentRequests: ConcurrentMap[FlightInfo, RequestHeader] = new ConcurrentHashMap()
  private[this] val sequence = new AtomicLong(0)

  def count: Int = currentRequests.size

  private[this] val lastAlert = new AtomicLong(-1)

  private[this] lazy val MaxMidFlightRequests = {
    val me = myInstanceInfo.get
    val max = me.info.instantTypeInfo.ecu * me.serviceType.loadFactor * 5
    log.info(s"allowing $max mid flight requests before blowing the whistle")
    max
  }

  def comingIn(rh: RequestHeader): FlightInfo = {
    val count = currentRequests.size() + 1 //may not be accurate since we're not synchronizing this block, but good enough
    val info = FlightInfo(sequence.getAndIncrement, System.currentTimeMillis(), count)
    currentRequests.put(info, rh)
    if (count > MaxMidFlightRequests) { //say that more then 30 concurrent request is an issue
      alert(info.concurrentFlights, rh: RequestHeader)
    }
    info
  }

  private[this] val TEN_MINUTES = 600000L

  private def alert(count: Long, rh: RequestHeader): Unit = {
    val now = System.currentTimeMillis()
    val last = lastAlert.get
    log.warn(s"$count concurrent request. latest: $rh")
    if (now - last > TEN_MINUTES) {
      if (lastAlert.compareAndSet(last, now)) {
        airbrake.get.notify(s"$count concurrent requests: $topRequests")
      }
    }
  }

  def topRequests: String = {
    val paths = currentRequests.values().toList map { rh => rh.path }
    val countedPaths = paths.foldLeft(Map.empty[String, Int]) { (m, x) => m + ((x, m.getOrElse(x, 0) + 1)) }
    countedPaths.toList.sortWith { case t => t._1._2 > t._2._2 }.map { t => s"${t._2}:${t._1}" }.mkString(",")
  }

  def goingOut(info: FlightInfo): Unit = currentRequests.remove(info)
}

case class FlightInfo(seqNum: Long, timestamp: Long, concurrentFlights: Int)

object EmptyFlightInfo extends FlightInfo(-1, 0, 0)
