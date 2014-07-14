package com.keepit.curator

import scala.slick.jdbc.JdbcBackend.{ Database => SlickDatabase }

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.db.slick.{ DbExecutionContext, DbInfo, SlickModule }

import akka.actor.ActorSystem
import play.api.Play
import play.api.db.DB

case class CuratorDbInfo() extends DbInfo {
  def masterDatabase = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))

  override def slaveDatabase = None

  def driverName = Play.current.configuration.getString("db.shoebox.driver").get
}

case class CuratorSlickModule() extends SlickModule(CuratorDbInfo()) {
  @Provides @Singleton
  def dbExecutionContextProvider(system: ActorSystem): DbExecutionContext =
    DbExecutionContext(system.dispatchers.lookup("db-thread-pool-dispatcher"))
}
