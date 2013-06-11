package com.keepit.inject

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Provides, Singleton}
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._

import akka.actor.ActorSystem
import akka.actor.Scheduler
import play.api.Play
import play.api.Play.current

class FortyTwoModule() extends ScalaModule {
  def configure(): Unit = {
    println("configuring FortyTwoModule")
    val appScope = new AppScope
    bindScope(classOf[AppScoped], appScope)
    bind[AppScope].toInstance(appScope)
  }

  @Provides
  @Singleton
  def fortyTwoServices(clock: Clock): FortyTwoServices =
    new FortyTwoServices(
      clock,
      current.mode,
      Play.resource("app_compilation_date.txt"),
      Play.resource("app_version.txt"))

  @Provides
  @AppScoped
  def schedulerProvider(system: ActorSystem): Scheduler = system.scheduler

}
