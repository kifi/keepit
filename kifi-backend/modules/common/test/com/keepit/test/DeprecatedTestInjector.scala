package com.keepit.test

import com.keepit.inject.EmptyInjector
import play.api.Mode
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.google.inject.util.Modules

trait DeprecatedTestInjector extends EmptyInjector {
  val mode = Mode.Test
  val module = Modules.combine(DeprecatedTestModule(), StandaloneTestActorSystemModule())
}
