package com.keepit.common.db

import com.keepit.common.db.slick._
import scala.slick.driver._

trait DatabaseDialect[T <: DataBaseComponent] {
  def stringToDay(str: String): String
}

object MySqlDatabaseDialect extends DatabaseDialect[H2] {
  def stringToDay(str: String): String = s"""STR_TO_DATE('$str', '%Y-%m-%d')"""
}

object H2DatabaseDialect extends DatabaseDialect[MySQL] {
  def stringToDay(str: String): String = s"""PARSEDATETIME('$str', 'y-M-d')"""
}

