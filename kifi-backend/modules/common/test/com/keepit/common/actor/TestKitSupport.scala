package com.keepit.common.actor

import akka.actor.ActorSystem
import akka.testkit.TestKit
import org.specs2.specification.After

// shutdown() is required to ensure there's no memory leak
abstract class TestKitSupport extends TestKit(ActorSystem()) with After {
  def after = system.shutdown
}