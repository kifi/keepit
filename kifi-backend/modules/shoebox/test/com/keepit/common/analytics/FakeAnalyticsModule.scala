package com.keepit.common.analytics

import net.codingwell.scalaguice.ScalaMultibinder

case class FakeAnalyticsModule() extends AnalyticsModule {
  def configure() {
    val noListener = ScalaMultibinder.newSetBinder[EventListener](binder)
  }
}
