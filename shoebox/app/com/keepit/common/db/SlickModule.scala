package com.keepit.common.db

import com.tzavellas.sse.guice.ScalaModule
import com.google.inject.{Provides, Inject, Singleton}
import com.keepit.common.time._
import org.joda.time.DateTime
import org.joda.time.LocalDate
import akka.actor.ActorSystem
import akka.actor.Scheduler
import org.scalaquery.session.Database
import org.scalaquery.session.Session
import org.scalaquery.session.ResultSetConcurrency
//import scala.slick.session.Database
//import scala.slick.session.Session
//import scala.slick.session.ResultSetConcurrency

case class SlickModule() extends ScalaModule {
  def configure(): Unit = {
  }

  @Provides
  @Singleton
  def database(): Database = Database.forURL("jdbc:h2:mem:shoebox;MODE=MYSQL;MVCC=TRUE", driver = "org.h2.Driver")

  @Provides
  @Singleton
  def readOnlyConnection(database: Database): ReadOnlyConnection = ReadOnlyConnection(database)

  @Provides
  @Singleton
  def readWriteConnection(database: Database): ReadWriteConnection = ReadWriteConnection(database)

}
