package com.keepit.common.db.slick

import com.keepit.common.db.{ DbSequence, DatabaseDialect }
import scala.slick.jdbc.JdbcBackend.{ Database => SlickDatabase }
import scala.slick.driver.JdbcDriver

//import scala.slick.session.{ Database => SlickDatabase, Session, ResultSetConcurrency, ResultSetType, ResultSetHoldability }

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
trait DataBaseComponent {
  // the actual driver implementation by Slick (e.g. H2 & MySQL)
  val Driver: JdbcDriver
  // dialect specific for this driver that Slick does not support
  val dialect: DatabaseDialect[_]
  // A database instance to which connections can be created.
  // Encapsulates either a DataSource or parameters for DriverManager.getConnection().
  val masterDb: SlickDatabase
  val replicaDb: Option[SlickDatabase]

  def getSequence[T](name: String): DbSequence[T]

  // MySQL and H2 have different preferences on casing the table and column names.
  // H2 specifically rather have them in upper case
  def entityName(name: String): String = name

  def initTable(tableName: String, ddl: { def createStatements: Iterator[String] }): Unit = {}
}
