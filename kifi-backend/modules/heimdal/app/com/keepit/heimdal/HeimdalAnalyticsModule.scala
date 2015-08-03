package com.keepit.heimdal

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.net.WebService
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import net.codingwell.scalaguice.ScalaModule
import play.api.Play.current

trait AnalyticsModule extends ScalaModule {
  @Provides @Singleton
  def amplitude(primaryOrgProvider: PrimaryOrgProvider, ws: WebService): AmplitudeClient = {
    val apiKey: String = current.configuration.getString("amplitude.api_key").get
    new AmplitudeClientImpl(apiKey, primaryOrgProvider, ws)
  }
}

case class ProdAnalyticsModule() extends AnalyticsModule {

  def configure() = {}

  @Provides @Singleton
  def mixpanel(primaryOrgProvider: PrimaryOrgProvider): MixpanelClient = {
    val projectToken: String = current.configuration.getString("mixpanel.token").get
    new MixpanelClientImpl(projectToken, primaryOrgProvider)
  }

  @Provides @Singleton
  def orgProvider(primaryOrgForUserCache: PrimaryOrgForUserCache, shoeboxServiceClient: ShoeboxServiceClient): PrimaryOrgProvider = {
    new PrimaryOrgProviderImpl(primaryOrgForUserCache, shoeboxServiceClient)
  }
}
