package com.keepit.common.db.slick

import scala.slick.lifted.DDL
import scala.slick.session.{Database => SlickDatabase}

import com.google.inject.{Provides, Singleton}
import com.keepit.common.db.slick._
import play.api.db.DB
import play.api.Play
import akka.actor.ActorSystem

trait DbInfo {
  def database: SlickDatabase
  def driverName: String
  def initTable[M](withDDL: {def ddl: DDL}): Unit = ???
}

case class ShoeboxDbInfo() extends DbInfo {
  def database = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
  def driverName = Play.current.configuration.getString("db.shoebox.driver").get
}
