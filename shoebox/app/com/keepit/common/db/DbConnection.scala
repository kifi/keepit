package com.keepit.common.db

//import scala.slick.session.{Database, Session, ResultSetConcurrency}
import org.scalaquery.session.{Database, Session, ResultSetConcurrency}
import javax.sql.DataSource

case class DbConnection(dbInfo: DbInfo) {

  def readOnly[T](f: Session => T): T = {
    val s = dbInfo.database.createSession.forParameters(rsConcurrency = ResultSetConcurrency.ReadOnly)
    try { f(s) } finally s.close()
  }

  def readWrite[T](f: Session => T): T = {
    val s = dbInfo.database.createSession.forParameters(rsConcurrency = ResultSetConcurrency.Updatable)
    try { f(s) } finally s.close()
  }
}

trait DbInfo {
  def database: Database
}
