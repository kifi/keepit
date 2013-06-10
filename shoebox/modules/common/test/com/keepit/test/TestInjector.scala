package com.keepit.test

import java.sql._

import net.codingwell.scalaguice.InjectorExtensions._

import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Module
import com.google.inject.Stage
import com.google.inject.util.Modules
import com.keepit.common.db.DbInfo
import com.keepit.common.db.slick.Database

import akka.actor.ActorSystem

trait TestInjector {

  def inject[A](implicit m: Manifest[A], injector: Injector): A = injector.instance[A]

  def withInjector[T](overridingModules: Module*)(f: Injector => T) = {
    def dbInfo: DbInfo = TestDbInfo.dbInfo
    DriverManager.registerDriver(new play.utils.ProxyDriver(Class.forName("org.h2.Driver").newInstance.asInstanceOf[Driver]))
    val modules = {
      def overrideModule(m: Module, overriding: Module) = Modules.`override`(Seq(m): _*).`with`(overriding)
      val init = overrideModule(TestModule(Some(dbInfo)), TestActorSystemModule(ActorSystem()))
      overridingModules.foldLeft(init)((init, over) => overrideModule(init, over))
    }

    implicit val injector = Guice.createInjector(Stage.DEVELOPMENT, modules)
    def db = injector.instance[Database]

    f(injector)
  }
}
