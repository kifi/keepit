package com.keepit.common.db

//import scala.slick.session.{Database, Session, ResultSetConcurrency}
import org.scalaquery.session.{Database, Session, ResultSetConcurrency}
import com.keepit.common.db.Session._

case class ReadOnlyConnection(database: Database) {
  def run[T](f: Session => T): T = {
    val s = database.createSession.forParameters(rsConcurrency = ResultSetConcurrency.ReadOnly)
    try { f(s) } finally s.close()
  }
}

case class ReadWriteConnection(database: Database) {
  def run[T](f: Session => T): T = {
    val s = database.createSession.forParameters(rsConcurrency = ResultSetConcurrency.Updatable)
    try { f(s) } finally s.close()
  }
}