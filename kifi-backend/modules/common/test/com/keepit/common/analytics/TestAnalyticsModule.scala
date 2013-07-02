package com.keepit.common.analytics

import com.keepit.inject.AppScoped
import net.codingwell.scalaguice.ScalaMultibinder

case class TestAnalyticsModule() extends AnalyticsModule {
  def configure() {
    bind[EventPersister].to[FakeEventPersisterImpl].in[AppScoped]
    val listenerBinder = ScalaMultibinder.newSetBinder[EventListener](binder)
  }
}
