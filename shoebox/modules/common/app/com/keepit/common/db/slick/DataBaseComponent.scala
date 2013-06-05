package com.keepit.common.db.slick

import com.google.inject.{Inject, Provider}
import com.keepit.common.db.{ DbSequence, DbInfo, DatabaseDialect }
import java.sql.{ PreparedStatement, Connection }
import scala.collection.mutable
import scala.slick.driver._
import scala.slick.session.{ Database => SlickDatabase, Session, ResultSetConcurrency, ResultSetType, ResultSetHoldability }
import scala.annotation.tailrec
import com.keepit.common.logging.Logging
import scala.util.Failure
import scala.util.Success
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException
import akka.actor.ActorSystem
import scala.concurrent._
import scala.slick.lifted.DDL
import scala.util.DynamicVariable
import com.keepit.common.healthcheck._
import play.api.Mode.Mode
import play.api.Mode.Test

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
trait DataBaseComponent {
  val Driver: ExtendedDriver
  val dialect: DatabaseDialect[_]
  def dbInfo: DbInfo
  lazy val handle: SlickDatabase = dbInfo.database

  def getSequence(name: String): DbSequence

  def entityName(name: String): String = name

  def initTable(table: TableWithDDL): Unit = {}
}
