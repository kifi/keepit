package com.keepit.common.analytics

import net.codingwell.scalaguice.ScalaMultibinder

case class TestAnalyticsModule() extends AnalyticsModule {
  def configure() {
    val listenerBinder = ScalaMultibinder.newSetBinder[EventListener](binder)
    listenerBinder.addBinding.to[ResultClickedListener]
  }
}
