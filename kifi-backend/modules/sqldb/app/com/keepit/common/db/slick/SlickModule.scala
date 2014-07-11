package com.keepit.common.db.slick

import scala.slick.jdbc.JdbcBackend.{ Database => SlickDatabase }
import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.db.slick._
import play.api.db.DB
import play.api.Play
import akka.actor.ActorSystem

abstract class SlickModule(dbInfo: DbInfo) extends ScalaModule {
  def configure(): Unit = {
    //see http://stackoverflow.com/questions/6271435/guice-and-scala-injection-on-generics-dependencies
    lazy val db = dbInfo.driverName match {
      case MySQL.driverName => new MySQL(dbInfo.masterDatabase, dbInfo.slaveDatabase)
      case H2.driverName => new H2(dbInfo.masterDatabase, dbInfo.slaveDatabase)
    }
    bind[DataBaseComponent].toInstance(db)
    bind[Database].in(classOf[Singleton])
  }
}
