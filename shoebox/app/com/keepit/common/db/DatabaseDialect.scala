package com.keepit.common.db

import com.keepit.common.db.slick._
import com.keepit.common.time._
import scala.slick.driver._
import org.joda.time._

trait DatabaseDialect[T <: DataBaseComponent] {
  def day(date: DateTime): String
}

object MySqlDatabaseDialect extends DatabaseDialect[MySQL] {
  def day(date: DateTime): String = s"""STR_TO_DATE('${date.toStandardDateString}', '%Y-%m-%d')"""
}

object H2DatabaseDialect extends DatabaseDialect[H2] {
  def day(date: DateTime): String = s"""PARSEDATETIME('${date.toStandardDateString}', 'y-M-d')"""
}

