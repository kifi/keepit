package com.keepit.common.db.slick

import scala.slick.session.{Database => SlickDatabase, Session, ResultSetConcurrency, ResultSetType, ResultSetHoldability}
import scala.slick.driver._
import scala.slick.lifted._
import scala.slick.SlickException
import java.sql.{PreparedStatement, Connection, DatabaseMetaData, Statement}
import com.google.inject.Inject
import com.keepit.common.db.DbInfo

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
trait DataBaseComponent {
  val Driver: ExtendedDriver
  def dbInfo: DbInfo
  lazy val handle: SlickDatabase = dbInfo.database

  def sequenceID: Column[Int]
  def entityName(name: String): String = name
}

class Database @Inject() (db: DataBaseComponent) {
  import DBSession._

  def readOnly[T](f: ROSession => T): T = {
    val s = db.handle.createSession.forParameters(rsConcurrency = ResultSetConcurrency.ReadOnly)
    try { f(new ROSession(s)) } finally s.close()
  }

  def readWrite[T](f: RWSession => T): T = {
    val s = db.handle.createSession.forParameters(rsConcurrency = ResultSetConcurrency.Updatable)
    try { f(new RWSession(s)) } finally s.close()
  }
}

object DBSession {
  abstract class SessionWrapper(val session: Session) extends Session {
    def conn: Connection = session.conn
    def metaData = session.metaData
    def capabilities = session.capabilities
    override def resultSetType = session.resultSetType
    override def resultSetConcurrency = session.resultSetConcurrency
    override def resultSetHoldability = session.resultSetHoldability
    def close() = throw new UnsupportedOperationException
    def rollback() = session.rollback
    def withTransaction[T](f: => T): T = session.withTransaction(f)
    override def forParameters(rsType: ResultSetType = resultSetType, rsConcurrency: ResultSetConcurrency = resultSetConcurrency,
      rsHoldability: ResultSetHoldability = resultSetHoldability) = throw new UnsupportedOperationException
  }

  abstract class RSession(roSession: Session) extends SessionWrapper(roSession)
  class ROSession(roSession: Session) extends RSession(roSession)
  class RWSession(rwSession: Session) extends RSession(rwSession)

  implicit def roToSession(roSession: ROSession): Session = roSession.session
  implicit def rwToSession(rwSession: RWSession): Session = rwSession.session
}
