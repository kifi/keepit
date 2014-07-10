package com.keepit.common.actor

import com.keepit.test._
import akka.actor._
import org.specs2.mutable.Specification

class MyTestActorA extends Actor {
  def receive = {
    case a => sender ! a
  }
}

class MyTestActorB extends Actor {
  def receive = {
    case a => sender ! a
  }
}

class ActorInstanceTest extends Specification with TestInjector {
  implicit val system = ActorSystem("test")

  "ActorInstance" should {
    "provide singletons" in {
      withInjector(StandaloneTestActorSystemModule()) { implicit injector =>
        val actorA = inject[ActorInstance[MyTestActorA]].ref
        val actorB = inject[ActorInstance[MyTestActorB]].ref
        //checking we're not getting the same reference fo rdiferant actor types
        actorA !== actorB
        val factoryA = inject[ActorInstance[MyTestActorA]]
        factoryA.ref === factoryA.ref
        //making sure the actor factory is a singleton
        factoryA.ref === actorA
        val factoryB = inject[ActorInstance[MyTestActorB]]
        factoryB.ref === factoryB.ref
        factoryB.ref === actorB
      }
    }
  }
}
