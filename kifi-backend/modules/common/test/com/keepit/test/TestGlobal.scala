package com.keepit.test

import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Module
import com.keepit.FortyTwoGlobal
import com.keepit.common.db.slick.Database

import play.api.Application
import play.api.Mode.Test
import play.utils.Threads

case class TestGlobal(modules: Module*) extends FortyTwoGlobal(Test) {

  override val initialized = true

  override lazy val injector: Injector = Guice.createInjector(modules: _*)

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    injector.instance[Database].readWrite { implicit session =>
      val conn = session.conn
      conn.createStatement().execute("DROP ALL OBJECTS")
    }
  }
}

case class TestRemoteGlobal(modules: Module*) extends FortyTwoGlobal(Test) {
  override val initialized = true
  override lazy val injector: Injector = Guice.createInjector(modules: _*)
}



