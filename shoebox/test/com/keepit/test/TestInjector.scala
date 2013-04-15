package com.keepit.test

import com.google.inject.Injector
import scala.slick.session.{Database => SlickDatabase}
import com.keepit.common.db.DbInfo
import com.google.inject.Guice
import com.keepit.common.db.slick.H2
import com.google.inject.Stage
import com.google.inject.util.Modules
import scala.slick.lifted.DDL
import com.keepit.inject.RichInjector
import com.keepit.common.db.slick.Database
import com.google.inject.Module
import com.keepit.inject.RichInjector
import java.sql.DriverManager

trait TestInjector {

  def inject[A](implicit m: Manifest[A], injector: RichInjector): A = injector.inject[A]

  def withInjector[T](overrideingModules: Module*)(f: RichInjector => T) = {
    Class.forName("org.h2.Driver")
    def dbInfo: DbInfo = TestDbInfo.dbInfo
//    val conn = DriverManager.getConnection(TestDbInfo.url)
    val modules = {
      def overridModule(m: Module, overriding: Module) = Modules.`override`(Seq(m): _*).`with`(overriding)
      val init = overridModule(TestModule(Some(dbInfo)), TestActorSystemModule())
      overrideingModules.foldLeft(init)((init, over) => overridModule(init, over))
    }

    implicit val injector = new RichInjector(Guice.createInjector(Stage.DEVELOPMENT, modules))
    def db = injector.inject[Database]

    f(injector)
  }
}
