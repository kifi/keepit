package com.keepit.test

import com.keepit.inject.EmptyInjector
import play.api.Mode
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.google.inject.util.Modules
import akka.actor.ActorSystem

trait DeprecatedTestInjector extends EmptyInjector {
  implicit val system = ActorSystem("test")
  val mode = Mode.Test
  val module = Modules.combine(DeprecatedTestModule(), StandaloneTestActorSystemModule())
}
