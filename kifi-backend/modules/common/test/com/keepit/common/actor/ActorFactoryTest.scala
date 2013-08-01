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

class ActorFactoryTest extends Specification with TestInjector {

  "ActorFactory" should {
    "provide singletons" in {
      withInjector(StandaloneTestActorSystemModule()) { implicit injector =>
      	val actorA = inject[ActorFactory[MyTestActorA]].actor
      	val actorB = inject[ActorFactory[MyTestActorB]].actor
      	//checking we're not getting the same reference fo rdiferant actor types
      	actorA !== actorB
      	val factoryA = inject[ActorFactory[MyTestActorA]]
      	factoryA.actor === factoryA.actor
      	//making sure the actor factory is a singleton
      	factoryA.actor === actorA
      	val factoryB = inject[ActorFactory[MyTestActorB]]
      	factoryB.actor === factoryB.actor
      	factoryB.actor === actorB
      }
    }   
  }
}