package com.keepit.common.db.slick

import com.google.inject.Inject
import com.keepit.common.db.{ DbSequence, DbInfo, DatabaseDialect }
import java.sql.{ PreparedStatement, Connection }
import scala.collection.mutable
import scala.slick.driver._
import scala.slick.session.{ Database => SlickDatabase, Session, ResultSetConcurrency, ResultSetType, ResultSetHoldability }
import scala.annotation.tailrec
import com.keepit.common.logging.Logging
import scala.util.Failure
import scala.util.Success
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException
import akka.actor.ActorSystem
import scala.concurrent._
import scala.util.DynamicVariable

// see https://groups.google.com/forum/?fromgroups=#!topic/scalaquery/36uU8koz8Gw
trait DataBaseComponent {
  val Driver: ExtendedDriver
  val dialect: DatabaseDialect[_]
  def dbInfo: DbInfo
  lazy val handle: SlickDatabase = dbInfo.database

  def getSequence(name: String): DbSequence

  def entityName(name: String): String = name
}

class InSessionException(message: String) extends Exception(message)

object DatabaseSessionLock {
  private val inSession = new DynamicVariable[Boolean](false)
  def enteringSession[T](f: => T) = {
    if (DatabaseSessionLock.inSession.value) throw new InSessionException("already in a DB session!")
    val res = inSession.withValue(true) { f }
    res
  }
}

class Database @Inject() (
    val db: DataBaseComponent,
    val system: ActorSystem
  ) extends Logging {

  import DBSession._

  implicit val executionContext = system.dispatchers.lookup("db-thread-pool-dispatcher")

  val dialect: DatabaseDialect[_] = db.dialect

  def readOnlyAsync[T](f: ROSession => T): Future[T] = future { readOnly(f) }
  def readWriteAsync[T](f: RWSession => T): Future[T] = future { readWrite(f) }
  def readWriteAsync[T](attempts: Int)(f: RWSession => T): Future[T] = future { readWrite(attempts)(f) }

  def readOnly[T](f: ROSession => T): T = DatabaseSessionLock.enteringSession {
    val s = db.handle.createSession.forParameters(rsConcurrency = ResultSetConcurrency.ReadOnly)
    try { f(new ROSession(s)) } finally s.close()
  }

  def readWrite[T](f: RWSession => T): T = DatabaseSessionLock.enteringSession {
    val s = db.handle.createSession.forParameters(rsConcurrency = ResultSetConcurrency.Updatable)
    try {
      s.withTransaction {
        f(new RWSession(s))
      }
    } finally s.close()
  }

  def readWrite[T](attempts: Int)(f: RWSession => T): T = {
    1 to attempts - 1 foreach { attempt =>
      try {
        return readWrite(f)
      } catch { case ex: MySQLIntegrityConstraintViolationException =>
        log.warn(s"Failed readWrite transaction attempt $attempt of $attempts")
      }
    }
    readWrite(f)
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

    private val statementCache = new mutable.HashMap[String, PreparedStatement]
    def getPreparedStatement(statement: String): PreparedStatement =
      statementCache.getOrElseUpdate(statement, this.conn.prepareStatement(statement))

    override def forParameters(rsType: ResultSetType = resultSetType, rsConcurrency: ResultSetConcurrency = resultSetConcurrency,
      rsHoldability: ResultSetHoldability = resultSetHoldability) = throw new UnsupportedOperationException
  }

  abstract class RSession(roSession: Session) extends SessionWrapper(roSession)
  class ROSession(roSession: Session) extends RSession(roSession)
  class RWSession(rwSession: Session) extends RSession(rwSession)

  implicit def roToSession(roSession: ROSession): Session = roSession.session
  implicit def rwToSession(rwSession: RWSession): Session = rwSession.session
}
