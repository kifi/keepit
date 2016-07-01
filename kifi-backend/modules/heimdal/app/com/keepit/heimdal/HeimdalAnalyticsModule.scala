package com.keepit.heimdal

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.net.WebService
import net.codingwell.scalaguice.ScalaModule
import play.api.Play.current

trait AnalyticsModule extends ScalaModule

case class ProdAnalyticsModule() extends AnalyticsModule {

  def configure() = {}

  @Provides @Singleton
  def mixpanel(): MixpanelClient = {
    val projectToken: String = current.configuration.getString("mixpanel.token").get
    new MixpanelClientImpl(projectToken)
  }
}
