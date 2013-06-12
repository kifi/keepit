package com.keepit.common.db

import scala.slick.lifted.DDL
import scala.slick.session.{Database => SlickDatabase}

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.Singleton
import com.keepit.common.db.slick._

class SlickModule(dbInfo: DbInfo) extends ScalaModule {
  def configure(): Unit = {
    //see http://stackoverflow.com/questions/6271435/guice-and-scala-injection-on-generics-dependencies
    bind[Database].in(classOf[Singleton])
    lazy val db = dbInfo.driverName match {
      case MySQL.driverName     => new MySQL( dbInfo )
      case H2.driverName        => new H2( dbInfo )
    }
    bind[DataBaseComponent].toInstance(db)
  }
}

trait DbInfo {
  def database: SlickDatabase
  def driverName: String
  def initTable[M](withDDL: {def ddl: DDL}): Unit = ???
}
