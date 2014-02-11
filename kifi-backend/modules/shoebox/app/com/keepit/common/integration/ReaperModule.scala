package com.keepit.common.integration

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.keepit.normalizer.{NormalizationUpdaterPluginImpl, NormalizationUpdaterPlugin}

trait ReaperModule extends ScalaModule

case class ProdReaperModule() extends ReaperModule {
  def configure() {
    bind[AutogenReaperPlugin].to[AutogenReaperPluginImpl].in[AppScoped]
    bind[NormalizationUpdaterPlugin].to[NormalizationUpdaterPluginImpl].in[AppScoped]
  }
}

case class DevReaperModule() extends ReaperModule {
  def configure() {
    bind[AutogenReaperPlugin].to[AutogenReaperPluginImpl].in[AppScoped]
    bind[NormalizationUpdaterPlugin].to[NormalizationUpdaterPluginImpl].in[AppScoped]
  }
}
