package com.keepit.common.actor

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.keepit.test.specs2.BeforeAllAfterAll
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.SpecificationLike
import org.specs2.time.NoTimeConversions
import play.api.Play

// shutdown() is required to ensure there's no memory leak
abstract class TestKitSupport(testSystem: ActorSystem = ActorTestSupport.getActorSystem()) extends TestKit(testSystem) with SpecificationLike with BeforeAllAfterAll with NoTimeConversions {
  override def afterAll = {
    system.shutdown
  }
}

object ActorTestSupport {
  def getActorSystem(appOpt: Option[play.api.Application] = Play.maybeApplication) = appOpt match {
    case Some(app) => ActorSystem("test-app-actor-system", app.configuration.underlying, app.classloader)
    case None => ActorSystem("test-standalone-actor-system", ConfigFactory.parseString("akka.log-dead-letters-during-shutdown=false"))
  }
}