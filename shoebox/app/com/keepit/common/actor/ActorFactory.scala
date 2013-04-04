package com.keepit.common.actor

import akka.actor._
import com.google.inject.{Provider, Inject}
import com.keepit.common.akka.FortyTwoActor

class ActorFactory[T <: FortyTwoActor] @Inject() (
    systemProvider: Provider[ActorSystem],
    provider: Provider[T]) {

  def system: ActorSystem = systemProvider.get

  def get(): ActorRef = system.actorOf(Props { provider.get() })

}
