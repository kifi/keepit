package com.keepit.inject

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Provides, Singleton, Provider}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import play.api.Play
import play.api.Mode.Mode
import play.api.Play.current

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
  def fortyTwoServices(clock: Clock): FortyTwoServices =
    new FortyTwoServices(
      clock,
      current.mode,
      Play.resource("app_compilation_date.txt"),
      Play.resource("app_version.txt"))

  @Provides @Singleton
  def playMode: Mode = current.mode

}
