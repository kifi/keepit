package com.keepit.common.db

//import scala.slick.session.{Database, Session, ResultSetConcurrency}
import org.scalaquery.session.{Database, Session, ResultSetConcurrency}

case class DbConnection(database: Database) {
  def readOnly[T](f: Session => T): T = {
    val s = database.createSession.forParameters(rsConcurrency = ResultSetConcurrency.ReadOnly)
    try { f(s) } finally s.close()
  }

  def readWrite[T](f: Session => T): T = {
    val s = database.createSession.forParameters(rsConcurrency = ResultSetConcurrency.Updatable)
    try { f(s) } finally s.close()
  }
}
