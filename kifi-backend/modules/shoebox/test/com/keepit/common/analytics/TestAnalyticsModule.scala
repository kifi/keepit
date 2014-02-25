package com.keepit.common.analytics

import net.codingwell.scalaguice.ScalaMultibinder

case class TestAnalyticsModule() extends AnalyticsModule {
  def configure() {
    val noListener = ScalaMultibinder.newSetBinder[EventListener](binder)
  }
}
