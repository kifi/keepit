package com.keepit.common.db.slick

import org.scalaquery.ql.extended.{ExtendedProfile => Profile}
import org.scalaquery.session.{Database, Session, ResultSetConcurrency, ResultSetType, ResultSetHoldability}
import org.scalaquery.ql._
import java.sql.{PreparedStatement, Connection, DatabaseMetaData, Statement}
import org.scalaquery.SQueryException

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
trait DataBaseComponent {
  val Driver: Profile
  def handle: Database

  val sequenceID: OperatorColumn[Int]
}

class DBConnection(db: DataBaseComponent) {
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
  class ROSession(val roSession: Session) extends Session {
    def conn: Connection = roSession.conn
    def metaData = roSession.metaData
    def capabilities = roSession.capabilities

    override def resultSetType = roSession.resultSetType
    override def resultSetConcurrency = roSession.resultSetConcurrency
    override def resultSetHoldability = roSession.resultSetHoldability
    def close() = roSession.close
    def rollback() = roSession.rollback
    def withTransaction[T](f: => T): T = roSession.withTransaction(f)
    override def forParameters(rsType: ResultSetType = resultSetType, rsConcurrency: ResultSetConcurrency = resultSetConcurrency,
      rsHoldability: ResultSetHoldability = resultSetHoldability) = roSession.forParameters(rsType, rsConcurrency, rsHoldability)
  }

  class RWSession(val rwSession: Session) extends ROSession(rwSession)

  implicit def roToSession(session: ROSession): Session = session.roSession
  implicit def rwToSession(session: RWSession): Session = session.rwSession
}