package com.keepit.common.db.slick

import org.scalaquery.ql.extended.{ExtendedProfile => Profile}
import org.scalaquery.session.{Database, Session, ResultSetConcurrency, ResultSetType, ResultSetHoldability}
import org.scalaquery.ql._
import java.sql.{PreparedStatement, Connection, DatabaseMetaData, Statement}
import org.scalaquery.SQueryException
import com.google.inject.Inject

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
trait DataBaseComponent {
  val Driver: Profile
  def handle: Database

  def sequenceID: OperatorColumn[Int]
}

class DBConnection @Inject() (db: DataBaseComponent) {
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
