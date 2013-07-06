package com.keepit.common.db

import scala.slick.lifted.DDL
import scala.slick.session.{Database => SlickDatabase}

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Provides, Singleton}
import com.keepit.common.db.slick._
import play.api.db.DB
import play.api.Play
import akka.actor.ActorSystem

abstract class SlickModule(dbInfo: DbInfo) extends ScalaModule {
  def configure(): Unit = {
    //see http://stackoverflow.com/questions/6271435/guice-and-scala-injection-on-generics-dependencies
    lazy val db = dbInfo.driverName match {
      case MySQL.driverName     => new MySQL(dbInfo)
      case H2.driverName        => new H2(dbInfo)
    }
    bind[Database].in(classOf[Singleton])
    bind[DataBaseComponent].toInstance(db)
  }
}

trait DbInfo {
  def database: SlickDatabase
  def driverName: String
  def initTable[M](withDDL: {def ddl: DDL}): Unit = ???
}

case class ShoeboxDbInfo() extends DbInfo {
  def database = SlickDatabase.forDataSource(DB.getDataSource("shoebox")(Play.current))
  def driverName = Play.current.configuration.getString("db.shoebox.driver").get
}

case class ShoeboxSlickModule() extends SlickModule(ShoeboxDbInfo()) {

  @Provides @Singleton
  def dbExecutionContextProvider(system: ActorSystem): DbExecutionContext = DbExecutionContext(system.dispatchers.lookup("db-thread-pool-dispatcher"))
}
