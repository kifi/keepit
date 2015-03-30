package com.keepit.test

import com.google.inject.util.Modules
import com.google.inject.{ Key, Injector, Module }
import com.keepit.common.concurrent.WatchableExecutionContext
import com.keepit.inject.InjectorProvider
import net.codingwell.scalaguice._
import net.codingwell.scalaguice.InjectorExtensions._

import scala.concurrent.ExecutionContext

trait TestInjectorProvider { this: InjectorProvider =>
  def inject[A](implicit m: Manifest[A], injector: Injector): A = injector.instance[A]
  def withInjector[T](overridingModules: Module*)(f: Injector => T) = {
    val customModules = Modules.`override`(module).`with`(overridingModules: _*)
    val injector = createInjector(customModules)
    try {
      f(injector)
    } finally {
      if (null != injector.getExistingBinding(Key.get(typeLiteral[ExecutionContext]))) {
        injector.instance[ExecutionContext] match {
          case watchable: WatchableExecutionContext =>
            val killed = watchable.kill()
            if (killed > 0) {
              println(s"[${getClass.getSimpleName}}] Killed $killed threads at the end of a test, should have those been running?")
            }
          case simple: ExecutionContext => new Exception(s"[${getClass.getSimpleName}] can't close execution context of type ${simple.getClass.getName}. Make sure you use FakeExecutionContextModule in the test!").printStackTrace
        }
      }
    }
  }
}
