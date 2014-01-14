package com.keepit.common.controller

import java.util.concurrent.atomic.{AtomicLong, AtomicInteger}
import com.google.inject.{Inject, Singleton}
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.mvc.RequestHeader

@Singleton
class MidFlightRequests @Inject() (airbrake: AirbrakeNotifier) {
  private val currentCount = new AtomicInteger(0)
  private val totalRequests = new AtomicLong(0L)
  def totalRequestsSoFar: Long = totalRequests.get()
  def comingIn(rh: RequestHeader): Int = {
    totalRequests.incrementAndGet()
    val count = currentCount.incrementAndGet()
    if (count > 100) { //say that more then 100 concurrent request is an issue
      airbrake.notify(s"There are $count concurrent requests on this service, $totalRequestsSoFar since start")
    }
    count
  }
  def goingOut(rh: RequestHeader): Int = {
    currentCount.decrementAndGet()
  }
}
