package com.keepit.akka

import akka.actor.ActorSystem
import org.specs2.mutable.After
import akka.testkit.TestKit
import org.specs2.time.NoTimeConversions
import org.specs2.specification.Scope

trait FutureTestScope extends Scope with NoTimeConversions
