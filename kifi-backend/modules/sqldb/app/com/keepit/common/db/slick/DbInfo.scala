package com.keepit.common.db.slick

import scala.slick.jdbc.JdbcBackend.{ Database => SlickDatabase }
import scala.slick.driver.JdbcDriver.DDL

trait DbInfo {
  def masterDatabase: SlickDatabase
  def slaveDatabase: Option[SlickDatabase] = None
  def driverName: String
  def initTable[M](withDDL: { def ddl: DDL }): Unit = ???
}
