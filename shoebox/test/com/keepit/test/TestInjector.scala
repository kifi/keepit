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

trait TestInjector {
  def withInjector[T](f: RichInjector => T) = {

    def dbInfo: DbInfo = new DbInfo() {
      lazy val database = SlickDatabase.forURL(url = "jdbc:h2:mem;MODE=MYSQL;MVCC=TRUE;DB_CLOSE_DELAY=-1")
      lazy val driverName = H2.driverName
    }

    implicit val injector = new RichInjector(Guice.createInjector(
        Stage.DEVELOPMENT,
        Modules.`override`(Seq(TestModule(Some(dbInfo))): _*).`with`(TestActorSystemModule())))
    def db = injector.inject[Database]

    f(injector)
  }
}