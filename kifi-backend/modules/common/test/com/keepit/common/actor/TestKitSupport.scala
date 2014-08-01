package com.keepit.common.actor

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.specs2.specification.After
import org.specs2.time.NoTimeConversions
import play.api.Play

// shutdown() is required to ensure there's no memory leak
abstract class TestKitSupport extends TestKit(ActorTestSupport.getActorSystem()) with After with NoTimeConversions {
  def after = system.shutdown
}

object ActorTestSupport {
  def getActorSystem(appOpt: Option[play.api.Application] = Play.maybeApplication) = appOpt match {
    case Some(app) => ActorSystem("test-app-actor-system", app.configuration.underlying, app.classloader)
    case None => ActorSystem("test-standalone-actor-system")
  }
}