package com.keepit.common.analytics

import net.codingwell.scalaguice.{ ScalaMultibinder, ScalaModule }

trait AnalyticsModule extends ScalaModule

case class ProdAnalyticsModule() extends AnalyticsModule {

  def configure() {
    val listenerBinder = ScalaMultibinder.newSetBinder[EventListener](binder)
    listenerBinder.addBinding.to[SliderShownListener]
  }
}

case class DevAnalyticsModule() extends AnalyticsModule {

  def configure() {
    val listenerBinder = ScalaMultibinder.newSetBinder[EventListener](binder)
    listenerBinder.addBinding.to[SliderShownListener]
  }
}
