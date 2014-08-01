package com.keepit.common.actor

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.specs2.specification.After
import org.specs2.time.NoTimeConversions

// shutdown() is required to ensure there's no memory leak
abstract class TestKitSupport extends TestKit(ActorSystem()) with After with NoTimeConversions {
  def after = system.shutdown
}
