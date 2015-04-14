package com.keepit.common.time

import scala.concurrent.duration._
import scala.util.Random

class RandomDelay(maxDelay: Duration) extends (() => Duration) {
  private val maxRandomDelaySeconds: Int = maxDelay.toSeconds.toInt
  def apply = Random.nextInt(maxRandomDelaySeconds) seconds
}
