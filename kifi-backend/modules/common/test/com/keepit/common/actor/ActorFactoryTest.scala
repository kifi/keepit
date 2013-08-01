package com.keepit.common.actor

import com.keepit.test._
import akka.actor._
import org.specs2.mutable.Specification
import com.google.inject.{Singleton, Provides}
import play.api.Play.current
import akka.testkit.TestKit

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

class ActorWrapperTest extends Specification with TestInjector {

  "ActorWrapper" should {
    "provide singletons" in {
      withInjector(StandaloneTestActorSystemModule()) { implicit injector =>
      	val actorA = inject[ActorWrapper[MyTestActorA]].actor
      	val actorB = inject[ActorWrapper[MyTestActorB]].actor
      	//checking we're not getting the same reference fo rdiferant actor types
      	actorA !== actorB
      	val factoryA = inject[ActorWrapper[MyTestActorA]]
      	factoryA.actor === factoryA.actor
      	//making sure the actor factory is a singleton
      	factoryA.actor === actorA
      	val factoryB = inject[ActorWrapper[MyTestActorB]]
      	factoryB.actor === factoryB.actor
      	factoryB.actor === actorB
      }
    }   
  }
}