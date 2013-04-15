package com.keepit.common.actor

import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.Provider
import com.keepit.common.akka.FortyTwoActor
import akka.testkit.TestActorRef

class TestActorBuilderImpl extends ActorBuilder {
  def apply(system: ActorSystem, provider: Provider[_ <: FortyTwoActor]): ActorRef =
    TestActorRef(provider.get)(system)
}
