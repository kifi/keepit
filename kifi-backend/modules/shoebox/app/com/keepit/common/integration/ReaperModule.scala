package com.keepit.common.integration

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.keepit.normalizer.{ NormalizationUpdaterPluginImpl, NormalizationUpdaterPlugin }
import com.google.inject.{ Provides, Singleton }
import play.api.Play.current

trait ReaperModule extends ScalaModule {
  @Singleton
  @Provides
  def autogenReaperConf: AutogenReaperConf = AutogenReaperConf(
    current.configuration.getBoolean("cron.reaper.sui.delete").getOrElse(false), // user and sui need to be consistent
    current.configuration.getBoolean("cron.reaper.user.delete").getOrElse(false)
  )
}

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
