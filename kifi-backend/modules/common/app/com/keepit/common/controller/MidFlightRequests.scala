package com.keepit.common.controller

import com.google.common.collect.MapMaker
import com.google.inject.{Provider, Inject, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.mvc.RequestHeader
import java.util.concurrent.ConcurrentMap
import scala.collection.JavaConversions._
import com.keepit.common.amazon.MyAmazonInstanceInfo
import com.keepit.common.logging.Logging

//todo(eishay): add alerts/stats on the longest outstanding request and/or average time using the value of the currentRequests map
@Singleton
class MidFlightRequests @Inject() (
    airbrake: Provider[AirbrakeNotifier],
    myInstanceInfo: Provider[MyAmazonInstanceInfo]) extends Logging {
  private val currentRequests: ConcurrentMap[RequestHeader, Long] = new MapMaker().concurrencyLevel(4).weakKeys().makeMap()

  def count: Int = currentRequests.size()
  @volatile private var lastAlert: Long = -1

  private lazy val MaxMidFlightRequests = {
    val max = myInstanceInfo.get.info.instantTypeInfo.ecu * 7
    log.info(s"allowing $max mid flight requests before blowing the whistle")
    max
  }

  def comingIn(rh: RequestHeader): Int = {
    currentRequests.put(rh, System.currentTimeMillis())
    val count = currentRequests.size() //may not be accurate since we're not synchronizing this block, but good enough
    if (count > MaxMidFlightRequests) { //say that more then 30 concurrent request is an issue
      alert(count)
    }
    count
  }

  private val TEN_MINUTES = 600000L

  private def alert(count: Long): Unit = {
    val now = System.currentTimeMillis()
    if (now - lastAlert > TEN_MINUTES) {
      synchronized {
        if (now - lastAlert > TEN_MINUTES) { //double check after getting into the synchronized block
          airbrake.get.notify(s"$count concurrent requests: $topRequests")
          lastAlert = System.currentTimeMillis()
        }
      }
    }
  }

  def topRequests: String = {
    val paths = currentRequests.keySet().toList map {rh => rh.path}
    val countedPaths = paths.foldLeft(Map.empty[String, Int]) { (m, x) => m + ((x, m.getOrElse(x, 0) + 1)) }
    countedPaths.toList.sortWith{case t => t._1._2 > t._2._2}.map{t => s"${t._2}:${t._1}"}.mkString(",")
  }

  def goingOut(rh: RequestHeader): Long = currentRequests.remove(rh)
}
