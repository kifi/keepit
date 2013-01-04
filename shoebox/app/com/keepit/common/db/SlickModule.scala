package com.keepit.common.db

import com.tzavellas.sse.guice.ScalaModule

import com.google.inject.{Provides, Inject, Singleton}
import com.keepit.common.time._
import com.keepit.common.db.slick._
import org.joda.time.DateTime
import org.joda.time.LocalDate
import akka.actor.ActorSystem
import akka.actor.Scheduler
import org.scalaquery.session.Database
import org.scalaquery.session.Session
import org.scalaquery.session.ResultSetConcurrency
import javax.sql.DataSource

case class SlickModule(dbInfo: DbInfo) extends ScalaModule {
  def configure(): Unit = {
  }

  @Provides
  @Singleton
  def connection(): DataBaseComponent = dbInfo.driverName match {
    case MySQL.driverName     => new MySQL( dbInfo )
    case H2.driverName        => new H2( dbInfo )
  }
}

trait DbInfo {
  def database: Database
  def driverName: String
}
