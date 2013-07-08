package com.keepit.common.analytics

import com.keepit.inject.AppScoped
import net.codingwell.scalaguice.ScalaMultibinder
import com.keepit.common.analytics.reports.{ReportBuilderPluginImpl, ReportBuilderPlugin}

case class TestAnalyticsModule() extends AnalyticsModule {
  def configure() {
    bind[EventPersister].to[FakeEventPersisterImpl].in[AppScoped]
    bind[ReportBuilderPlugin].to[ReportBuilderPluginImpl].in[AppScoped]
    val listenerBinder = ScalaMultibinder.newSetBinder[EventListener](binder)
  }
}
