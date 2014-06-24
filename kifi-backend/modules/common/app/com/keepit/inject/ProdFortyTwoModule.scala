package com.keepit.inject

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Provides, Singleton, Provider}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import play.api.Play
import play.api.Play.current

case class FortyTwoConfig(
  applicationName: String,
  applicationBaseUrl: String)

trait FortyTwoModule extends ScalaModule {

  def configure(): Unit = {
    val appScope = new AppScope
    super.bindScope(classOf[AppScoped], appScope)
    bind[AppScope].toInstance(appScope)
    bind[play.api.Application].toProvider(new Provider[play.api.Application] {
      def get(): play.api.Application = current
    }).in(classOf[AppScoped])
  }
}

case class ProdFortyTwoModule() extends FortyTwoModule {

  @Provides @Singleton
  def fortyTwoServices(clock: Clock, fortytwoConfig: FortyTwoConfig): FortyTwoServices =
    new FortyTwoServices(
      clock,
      current.mode,
      Play.resource("app_compilation_date.txt"),
      Play.resource("app_version.txt"),
      fortytwoConfig)

  @Provides
  @Singleton
  def fortytwoConfig: FortyTwoConfig = FortyTwoConfig(
    current.configuration.getString("application.name").get,
    current.configuration.getString("application.baseUrl").get
  )
}
