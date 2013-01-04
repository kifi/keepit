package com.keepit.common.db.slick

import org.scalaquery.ql.extended.{ExtendedProfile => Profile}
import org.scalaquery.session.{Database, Session, ResultSetConcurrency}
import org.scalaquery.ql._

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
trait DataBaseComponent {
  val Driver: Profile
  def handle: Database

  val sequenceID: OperatorColumn[Int]

  def readOnly[T](f: Session => T): T = {
    val s = handle.createSession.forParameters(rsConcurrency = ResultSetConcurrency.ReadOnly)
    try { f(s) } finally s.close()
  }

  def readWrite[T](f: Session => T): T = {
    val s = handle.createSession.forParameters(rsConcurrency = ResultSetConcurrency.Updatable)
    try { f(s) } finally s.close()
  }
}

