package com.keepit.common.controller

import java.util.concurrent.atomic.{AtomicLong, AtomicInteger}
import com.google.inject.{Inject, Singleton}

@Singleton
class MidFlightRequests @Inject() () {
  private val currentCount = new AtomicInteger(0)
  private val totalRequests = new AtomicLong(0L)
  def totalRequestsSoFar: Long = totalRequests.get()
  def comingIn(): Int = {
    totalRequests.incrementAndGet()
    currentCount.incrementAndGet()
  }
  def goingOut(): Int = {
    currentCount.decrementAndGet()
  }
}
