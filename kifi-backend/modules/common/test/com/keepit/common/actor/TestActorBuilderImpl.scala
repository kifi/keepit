package com.keepit.common.actor

import akka.actor.{ Props, ActorRef, ActorSystem, Actor }
import com.google.inject.Provider
import akka.testkit.TestActorRef

class TestActorBuilderImpl extends ActorBuilder {
  def apply(system: ActorSystem, provider: Provider[_ <: Actor]): ActorRef = {
    TestActorRef(Props { provider.get })(system)
  }

}
