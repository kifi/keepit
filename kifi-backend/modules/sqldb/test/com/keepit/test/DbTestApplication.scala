package com.keepit.test

import com.google.inject.Module
import com.keepit.common.db.slick._
import play.api.{ Application }
import play.utils.Threads
import java.io.File
import net.codingwell.scalaguice.InjectorExtensions._

private class TestGlobalWithDB(defaultModules: Seq[Module], overridingModules: Seq[Module])
    extends TestGlobal(defaultModules, overridingModules) {

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    injector.instance[Database].readWrite { implicit session =>
      val conn = session.conn
      conn.createStatement().execute("DROP ALL OBJECTS")
    }
  }
}

class DbTestApplication(path: File, overridingModules: Seq[Module], defaultModules: Seq[Module])
  extends TestApplicationFromGlobal(path, new TestGlobalWithDB(defaultModules, overridingModules))
