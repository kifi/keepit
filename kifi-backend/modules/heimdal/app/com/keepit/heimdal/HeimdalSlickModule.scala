package com.keepit.heimdal

import com.keepit.common.db.slick._
import scala.util._
import scala.slick.jdbc.JdbcBackend.{Database => SlickDatabase}

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Provides, Singleton}
import com.keepit.common.db.slick._
import play.api.db.DB
import play.api.Play
import akka.actor.ActorSystem

case class HeimdalDbInfo() extends DbInfo {
  def masterDatabase = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
  // can't probe for existing (or not) db, must try and possibly fail.
  override def slaveDatabase = None

  def driverName = Play.current.configuration.getString("db.shoebox.driver").get
}

case class HeimdalSlickModule() extends SlickModule(HeimdalDbInfo()) {
  @Provides @Singleton
  def dbExecutionContextProvider(system: ActorSystem): DbExecutionContext =
    DbExecutionContext(system.dispatchers.lookup("db-thread-pool-dispatcher"))
}
