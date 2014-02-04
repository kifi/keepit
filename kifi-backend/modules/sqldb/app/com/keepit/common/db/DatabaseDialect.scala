package com.keepit.common.db

import com.keepit.common.db.slick._
import com.keepit.common.time._
import scala.slick.driver._
import org.joda.time._

trait DatabaseDialect[T <: DataBaseComponent] {
  def day(date: DateTime): String
  def dateTime(date: DateTime): String
}

object MySqlDatabaseDialect extends DatabaseDialect[MySQL] {
  def day(date: DateTime): String = s"""STR_TO_DATE('${date.toStandardDateString}', '%Y-%m-%d')"""
  def dateTime(date: DateTime): String = s"""STR_TO_DATE('${date.toStandardTimeString}', '%Y-%m-%dT%H:%i:%s')""" // no milliseconds!
}

object H2DatabaseDialect extends DatabaseDialect[H2] {
  def day(date: DateTime): String = s"""PARSEDATETIME('${date.toStandardDateString}', 'y-M-d')"""

  // H2 uses SimpleDateFormat which is *not* ISO-8601 compatible!
  def dateTime(date: DateTime): String = s"""PARSEDATETIME('${date.toStandardTimeString.replace('T',' ').replace("Z", "")}', 'yyyy-MM-d hh:mm:ss.SSS')"""
}

