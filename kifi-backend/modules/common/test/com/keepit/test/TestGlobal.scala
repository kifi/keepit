package com.keepit.test

import com.google.inject.Module
import com.keepit.FortyTwoGlobal
import com.keepit.common.db.slick.Database

import play.api.Application
import play.api.Mode.Test
import play.utils.Threads

@deprecated("Use TestGlobalWithDb instead", "July 3rd 2013")
case class TestGlobal(val modules: Module*) extends FortyTwoGlobal(Test) {

  override val initialized = true

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    injector.instance[Database].readWrite { implicit session =>
      val conn = session.conn
      conn.createStatement().execute("DROP ALL OBJECTS")
    }
  }
}

@deprecated("Use SimpleTestGlobal instead", "July 3rd 2013")
case class TestRemoteGlobal(val modules: Module*) extends FortyTwoGlobal(Test) {
  override val initialized = true
}



