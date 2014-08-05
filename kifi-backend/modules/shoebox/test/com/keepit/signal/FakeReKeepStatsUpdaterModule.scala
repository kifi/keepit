package com.keepit.signal

import com.keepit.inject.AppScoped

case class FakeReKeepStatsUpdaterModule() extends ReKeepStatsUpdaterModule {
  def configure(): Unit = {
    bind[ReKeepStatsUpdaterPlugin].to[ReKeepStatsUpdaterPluginImpl].in[AppScoped]
  }
}
