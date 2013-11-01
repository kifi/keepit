package com.keepit.reports

import com.keepit.inject.AppScoped

import net.codingwell.scalaguice.ScalaModule

case class GeckoboardModule() extends ScalaModule {

  def configure {
    bind[GeckoboardReporterPlugin].to[GeckoboardReporterPluginImpl].in[AppScoped]
  }

}
