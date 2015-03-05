package com.keepit.rover

import com.keepit.common.db.slick._
import scala.slick.jdbc.JdbcBackend.{ Database => SlickDatabase }

import com.google.inject.{ Provides, Singleton }
import play.api.db.DB
import play.api.Play
import akka.actor.ActorSystem

case class RoverDbInfo() extends DbInfo {
  def masterDatabase = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
  // can't probe for existing (or not) db, must try and possibly fail.
  override def slaveDatabase = None

  def driverName = Play.current.configuration.getString("db.shoebox.driver").get
}

case class RoverSlickModule() extends SlickModule(RoverDbInfo()) {
  @Provides @Singleton
  def dbExecutionContextProvider(system: ActorSystem): DbExecutionContext =
    DbExecutionContext(system.dispatchers.lookup("db-thread-pool-dispatcher"))
}
