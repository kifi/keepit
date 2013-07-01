package com.keepit.inject

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Provides, Singleton, Provider}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import play.api.Play
import play.api.Mode.Mode
import play.api.Play.current

case class FortyTwoModule() extends ScalaModule {
  def configure(): Unit = {
    val appScope = new AppScope
    bindScope(classOf[AppScoped], appScope)
    bind[AppScope].toInstance(appScope)
    bind[play.api.Application].toProvider(new Provider[play.api.Application] {
      def get(): play.api.Application = current
    }).in(classOf[AppScoped])
  }

  @Provides
  @Singleton
  def fortyTwoServices(clock: Clock): FortyTwoServices =
    new FortyTwoServices(
      clock,
      current.mode,
      Play.resource("app_compilation_date.txt"),
      Play.resource("app_version.txt"))

  @Singleton
  @Provides
  def playMode: Mode = current.mode

}
