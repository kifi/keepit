package com.keepit.akka

import akka.actor.ActorSystem
import org.specs2.mutable.After
import akka.testkit.TestKit
import org.specs2.time.NoTimeConversions
import org.specs2.specification.Scope

class TestKitScope(implicit system: ActorSystem) extends TestKit(system) with Scope with NoTimeConversions with After {
  def after = system.shutdown()
}
