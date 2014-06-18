package com.keepit.signal

import com.keepit.inject.AppScoped

case class TestReKeepStatsUpdaterModule() extends ReKeepStatsUpdaterModule {
  def configure(): Unit = {
    bind[ReKeepStatsUpdaterPlugin].to[ReKeepStatsUpdaterPluginImpl].in[AppScoped]
  }
}
