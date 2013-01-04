package com.keepit.common.db.slick

import org.scalaquery.ql.extended.{ExtendedProfile => Profile}
import org.scalaquery.session.Database
import org.scalaquery.ql._

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
trait DataBaseComponent {
  val Driver: Profile
  def handle: Database

  val sequenceID: OperatorColumn[Int]
}

import javax.sql._
trait DataSourceComponent {
  def getConnection(ds: DataSource): Database
}
