package com.keepit.common.db.slick

import org.scalaquery.ql._
import org.scalaquery.ql.SimpleFunction
import org.scalaquery.session.Database
import org.scalaquery.ql.extended.MySQLDriver

import javax.sql._

import _root_.com.keepit.common.db.DbInfo

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
class MySQL(val dbInfo: DbInfo)
    extends DataBaseComponent {
  println("initiating MySQL driver")
  val Driver = MySQLDriver
  val handle = dbInfo.database

  val sequenceID = SimpleFunction.nullary[Int]("LAST_INSERT_ID")
}

object MySQL {
  val driverName = "com.mysql.jdbc.Driver"
}

