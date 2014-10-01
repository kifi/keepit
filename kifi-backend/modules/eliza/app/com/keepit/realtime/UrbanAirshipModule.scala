package com.keepit.realtime

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.Provides
import com.google.inject.Singleton
import com.keepit.inject.AppScoped

trait UrbanAirshipModule extends ScalaModule

case class ElizaUrbanAirshipModule() extends UrbanAirshipModule {
  def configure() {
  }

  @Provides @Singleton def urbanAirshipConfig(app: play.api.Application): UrbanAirshipConfig = {
    val config = app.configuration.getConfig("urban-airship").get
    UrbanAirshipConfig(config.getString("key").get, config.getString("secret").get, config.getString("dev_key").get, config.getString("dev_secret").get)
  }
}
