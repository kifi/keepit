package com.keepit.common.db.slick

import scala.slick.driver.H2Driver
import scala.slick.lifted._

import javax.sql._

import _root_.com.keepit.common.db.DbInfo

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
class H2(val dbInfo: DbInfo)
    extends DataBaseComponent {
  println("initiating H2 driver")
  val Driver = H2Driver

  override def entityName(name: String): String = name.toUpperCase()
}

object H2 {
  val driverName = "org.h2.Driver"
}

