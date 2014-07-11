package com.keepit.test

import net.codingwell.scalaguice.{ ScalaMultibinder, ScalaModule }
import com.keepit.inject.{ TestFortyTwoModule, ApplicationInjector, EmptyInjector }
import com.keepit.common.db.{ TestDbInfo }
import java.sql.{ Driver, DriverManager }
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession.RWSession

import com.keepit.common.db.TestSlickModule
import com.google.inject.util.Modules
import com.google.inject.Module

import play.api.{ Application, Mode }
import play.utils.Threads

class TestGlobalWithDB(defaultModules: Seq[Module], overridingModules: Seq[Module])
    extends TestGlobal(defaultModules, overridingModules) {

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    injector.instance[Database].readWrite { implicit session =>
      val conn = session.conn
      conn.createStatement().execute("DROP ALL OBJECTS")
    }
  }
}
