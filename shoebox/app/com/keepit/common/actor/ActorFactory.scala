package com.keepit.common.actor

import akka.actor._
import akka.testkit.TestActorRef
import com.google.inject.{ImplementedBy, Provider, Inject}
import com.keepit.common.akka.AlertingActor

class ActorFactory[T <: AlertingActor] @Inject() (
    systemProvider: Provider[ActorSystem],
    builder: ActorBuilder,
    provider: Provider[T]) {

  def system: ActorSystem = systemProvider.get
  def get(): ActorRef = builder(system, provider)
}

@ImplementedBy(classOf[ActorBuilderImpl])
trait ActorBuilder {
  def apply(system: ActorSystem, provider: Provider[_ <: AlertingActor]): ActorRef
}

class ActorBuilderImpl extends ActorBuilder {
  def apply(system: ActorSystem, provider: Provider[_ <: AlertingActor]): ActorRef =
    system.actorOf(Props { provider.get })
}
