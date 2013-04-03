package com.keepit.common.actor

import akka.actor._
import com.google.inject.{Provider, Inject}
import com.keepit.common.akka.FortyTwoActor

class ActorFactory[T <: FortyTwoActor] @Inject() (val system: ActorSystem, provider: Provider[T]) {
  def get() = system.actorOf(Props { provider.get() })
}
