package com.keepit.common.db

//import scala.slick.session.{Database, Session, ResultSetConcurrency}
import org.scalaquery.session.{Database, Session, ResultSetConcurrency}

case class DbConnection(dbInfo: DbInfo) {

  lazy val database: Database = Database.forURL(dbInfo.url, driver = dbInfo.driver)

  def readOnly[T](f: Session => T): T = {
    val s = database.createSession.forParameters(rsConcurrency = ResultSetConcurrency.ReadOnly)
    try { f(s) } finally s.close()
  }

  def readWrite[T](f: Session => T): T = {
    val s = database.createSession.forParameters(rsConcurrency = ResultSetConcurrency.Updatable)
    try { f(s) } finally s.close()
  }
}

trait DbInfo {
  def url: String
  def driver: String
  def user: String = null
  def password: String = null
}
