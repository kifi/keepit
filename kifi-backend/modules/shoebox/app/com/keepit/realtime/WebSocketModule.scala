package com.keepit.realtime

import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped

trait WebSocketModule extends ScalaModule

case class ShoeboxWebSocketModule() extends WebSocketModule {
  def configure() {
    bind[UserEmailNotifierPlugin].to[UserEmailNotifierPluginImpl].in[AppScoped]
    bind[NotificationConsistencyChecker].to[NotificationConsistencyCheckerImpl].in[AppScoped]
    bind[ChannelPlugin].to[ChannelPluginImpl].in[AppScoped]
  }
}
