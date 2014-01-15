package com.keepit.common.controller

import com.google.common.collect.MapMaker
import com.google.inject.{Inject, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.mvc.RequestHeader
import java.util.concurrent.ConcurrentMap
import scala.collection.JavaConversions._

//todo(eishay): add alerts/stats on the longest outstanding request and/or average time using the value of the currentRequests map
@Singleton
class MidFlightRequests @Inject() (airbrake: AirbrakeNotifier) {
  private val currentRequests: ConcurrentMap[RequestHeader, Long] = new MapMaker().concurrencyLevel(4).weakKeys().makeMap()

  def count: Int = currentRequests.size()
  @volatile private var lastAlert: Long = -1

  def comingIn(rh: RequestHeader): Int = {
    currentRequests.put(rh, System.currentTimeMillis())
    val count = currentRequests.size() //may not be accurate since we're not synchronizing this block, but good enough
    if (count > 30) { //say that more then 30 concurrent request is an issue
      alert(count)
    }
    count
  }

  private def alert(count: Long): Unit = {
    val now = System.currentTimeMillis()
    if (now - lastAlert > 600000) { //10 minutes
      synchronized {
        if (now - lastAlert > 600000) { //10 minutes - double check after getting into the synchronized block
          airbrake.notify(s"$count concurrent requests: $topRequests")
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
