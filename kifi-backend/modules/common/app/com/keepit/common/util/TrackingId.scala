package com.keepit.common.util

import java.util.Random

object TrackingId {

  private[this] val rnd = new Random(System.currentTimeMillis)
  private[this] val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

  def get(): String = {
    val buf = new Array[Char](5)
    val len = chars.length
    var i = 0
    var value = rnd.nextInt(Int.MaxValue)
    while (i < 5) {
      buf(i) = chars.charAt(value % len)
      value = value / len
      i += 1
    }
    new String(buf)
  }

}
