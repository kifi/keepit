package com.keepit.common.db.slick

import com.google.inject.{Inject, Provider}
import com.keepit.common.db.{ DbSequence, DatabaseDialect }
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
  // the actual driver implementation by Slick (e.g. H2 & MySQL)
  val Driver: ExtendedDriver
  // dialect specific for this driver that Slick does not support
  val dialect: DatabaseDialect[_]

  val handle: SlickDatabase

  def getSequence(name: String): DbSequence

  // MySQL and H2 have different preferences on casing the table and column names.
  // H2 specifically rather have them in upper case
  def entityName(name: String): String = name

  def initTable(table: TableWithDDL): Unit = {}
}
