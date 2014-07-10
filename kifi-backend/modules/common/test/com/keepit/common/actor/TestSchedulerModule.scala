package com.keepit.common.actor

import akka.actor.{ Cancellable, Scheduler, ActorSystem }
import com.google.inject.Provides
import com.keepit.common.plugin.{ SchedulingPropertiesImpl, SchedulingProperties }
import com.keepit.inject.AppScoped
import play.api.Play.current
import scala.concurrent.future
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.zookeeper.ServiceDiscovery
import net.codingwell.scalaguice.ScalaModule

case class TestSchedulerModule() extends ScalaModule {

  def configure() {
    bind[Scheduler].to[FakeScheduler]
  }

}
