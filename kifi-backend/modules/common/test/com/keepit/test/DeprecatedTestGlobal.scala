package com.keepit.test

import com.google.inject.Module
import com.keepit.FortyTwoGlobal
import com.keepit.common.db.slick.Database

import play.api.Application
import play.api.Mode.Test
import play.utils.Threads
import com.google.inject.util.Modules

case class DeprecatedTestGlobal(modules: Module*) extends FortyTwoGlobal(Test) {
  val module = Modules.combine(modules:_*)

  override val initialized = true

  override def onStop(app: Application): Unit = Threads.withContextClassLoader(app.classloader) {
    injector.instance[Database].readWrite { implicit session =>
      val conn = session.conn
      conn.createStatement().execute("DROP ALL OBJECTS")
    }
  }
}

case class DeprecatedTestRemoteGlobal(modules: Module*) extends FortyTwoGlobal(Test) {
  val module = Modules.combine(modules:_*)
  override val initialized = true
}



