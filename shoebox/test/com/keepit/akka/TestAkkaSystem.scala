package com.keepit.akka

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

trait TestAkkaSystem {

  implicit val system = ActorSystem("testsystem", ConfigFactory.parseString("""
      akka.event-handlers = ["akka.testkit.TestEventListener"]
      """))

}
