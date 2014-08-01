package com.keepit.abook

import akka.actor.ActorSystem
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.db.slick._
import play.api.Play
import play.api.db.DB

import scala.slick.jdbc.JdbcBackend.{ Database => SlickDatabase }

case class ABookDbInfo() extends DbInfo {
  def masterDatabase = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
  // can't probe for existing (or not) db, must try and possibly fail.
  override def slaveDatabase = None

  def driverName = Play.current.configuration.getString("db.shoebox.driver").get
}

case class ABookSlickModule() extends SlickModule(ABookDbInfo()) {
  @Provides @Singleton
  def dbExecutionContextProvider(system: ActorSystem): DbExecutionContext =
    DbExecutionContext(system.dispatchers.lookup("db-thread-pool-dispatcher"))
}

