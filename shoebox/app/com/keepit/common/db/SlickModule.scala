package com.keepit.common.db

import com.tzavellas.sse.guice.ScalaModule
import com.google.inject.{Provides, Inject, Singleton, TypeLiteral}
import com.keepit.common.db.slick.Repo
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.common.db.slick._
import org.joda.time.DateTime
import org.joda.time.LocalDate
import akka.actor.ActorSystem
import akka.actor.Scheduler
import javax.sql.DataSource
import scala.slick.session.{Database => SlickDatabase}
import scala.slick.lifted.DDL

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
