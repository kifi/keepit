package com.keepit.common.db.slick

import org.scalaquery.ql._
import org.scalaquery.ql.SimpleFunction
import org.scalaquery.session.Database
import org.scalaquery.ql.extended.H2Driver

import javax.sql._

import _root_.com.keepit.common.db.DbInfo

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
class H2(val dbInfo: DbInfo)
    extends DataBaseComponent {
  println("initiating H2 driver")
  val Driver = H2Driver
  lazy val handle = dbInfo.database

  lazy val sequenceID = SimpleFunction.nullary[Int]("SCOPE_IDENTITY")
}

object H2 {
  val driverName = "org.h2.Driver"
}

