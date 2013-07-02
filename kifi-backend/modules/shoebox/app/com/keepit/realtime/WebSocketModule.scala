package com.keepit.realtime

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.Provides
import com.google.inject.Singleton
import com.keepit.inject.AppScoped

trait WebSocketModule extends ScalaModule

case class ShoeboxWebSocketModule() extends WebSocketModule {
  def configure() {
    bind[UserEmailNotifierPlugin].to[UserEmailNotifierPluginImpl].in[AppScoped]
    bind[NotificationConsistencyChecker].to[NotificationConsistencyCheckerImpl].in[AppScoped]
    bind[ChannelPlugin].to[ChannelPluginImpl].in[AppScoped]
  }

  @Provides @Singleton def urbanAirshipConfig(app: play.api.Application): UrbanAirshipConfig = {
    val config = app.configuration.getConfig("urban-airship").get
    UrbanAirshipConfig(config.getString("key").get, config.getString("secret").get)
  }
}
