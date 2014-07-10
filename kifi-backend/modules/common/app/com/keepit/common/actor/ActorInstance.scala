package com.keepit.common.actor

import akka.actor._
import akka.testkit.TestActorRef
import com.google.inject.{ ImplementedBy, Provider, Inject, Singleton }

@Singleton
class ActorInstance[+T <: Actor] @Inject() (
    systemProvider: Provider[ActorSystem],
    builder: ActorBuilder,
    provider: Provider[T]) {

  lazy val system: ActorSystem = systemProvider.get
  lazy val ref: ActorRef = builder(system, provider)
}

@ImplementedBy(classOf[ActorBuilderImpl])
trait ActorBuilder {
  def apply(system: ActorSystem, provider: Provider[_ <: Actor]): ActorRef
}

class ActorBuilderImpl extends ActorBuilder {
  def apply(system: ActorSystem, provider: Provider[_ <: Actor]): ActorRef =
    system.actorOf(Props { provider.get })
}
