package com.keepit.test


import net.codingwell.scalaguice.InjectorExtensions._

import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Module
import com.google.inject.Stage
import com.google.inject.util.Modules

import akka.actor.ActorSystem

trait TestInjector {

  def inject[A](implicit m: Manifest[A], injector: Injector): A = injector.instance[A]

  def withInjector[T](overridingModules: Module*)(f: Injector => T) = {
    val modules = {
      def overrideModule(m: Module, overriding: Module) = Modules.`override`(Seq(m): _*).`with`(overriding)
      val init = overrideModule(TestModule(), TestActorSystemModule(ActorSystem()))
      overridingModules.foldLeft(init)((init, over) => overrideModule(init, over))
    }

    implicit val injector = Guice.createInjector(Stage.DEVELOPMENT, modules)

    f(injector)
  }
}
