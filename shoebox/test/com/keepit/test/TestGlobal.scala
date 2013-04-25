package com.keepit.test

import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Module
import com.keepit.FortyTwoGlobal
import play.api.Mode.Test
import play.utils.Threads
import play.api.Application
import com.keepit.inject.RichInjector
import com.keepit.common.db.slick.Database

case class TestGlobal(modules: Module*) extends FortyTwoGlobal(Test) {

  override val initialized = true

  override lazy val injector: Injector = Guice.createInjector(modules: _*)

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    injector.inject[Database].readWrite { implicit session =>
      val conn = session.conn
      conn.createStatement().execute("DROP ALL OBJECTS")
    }
  }
}


