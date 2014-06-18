package com.keepit.signal

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped

trait ReKeepStatsUpdaterModule extends ScalaModule

case class ProdReKeepStatsUpdaterModule() extends ReKeepStatsUpdaterModule {
  def configure {
    bind[ReKeepStatsUpdaterPlugin].to[ReKeepStatsUpdaterPluginImpl].in[AppScoped]
  }
}

case class DevReKeepStatsUpdaterModule() extends ReKeepStatsUpdaterModule {
  def configure {
    bind[ReKeepStatsUpdaterPlugin].to[ReKeepStatsUpdaterPluginImpl].in[AppScoped]
  }
}