package com.keepit.common.db.slick

import scala.concurrent._
import scala.slick.session.ResultSetConcurrency
import scala.slick.session.Session
import scala.slick.session.{Database => SlickDatabase}
import scala.util.DynamicVariable
import com.google.inject.{Singleton, ImplementedBy, Inject, Provider}
import com.keepit.common.db.DatabaseDialect
import com.keepit.common.logging.Logging
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException
import java.sql.SQLException
import play.api.Mode.Mode
import play.api.Mode.Test
import play.modules.statsd.api.Statsd
import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import scala.util.Success
import scala.util.Failure

class InSessionException(message: String) extends Exception(message)

object DatabaseSessionLock {
  val inSession = new DynamicVariable[Boolean](false)
}

// this allows us to replace the database session implementation in tests and check when sessions are being obtained
@ImplementedBy(classOf[SlickSessionProviderImpl])
trait SlickSessionProvider {
  def createReadOnlySession(handle: SlickDatabase): Session
  def createReadWriteSession(handle: SlickDatabase): Session
}

@Singleton
class SlickSessionProviderImpl extends SlickSessionProvider {
  def createReadOnlySession(handle: SlickDatabase): Session = {
    handle.createSession().forParameters(rsConcurrency = ResultSetConcurrency.ReadOnly)
  }
  def createReadWriteSession(handle: SlickDatabase): Session = {
    handle.createSession().forParameters(rsConcurrency = ResultSetConcurrency.Updatable)
  }
}

case class DbExecutionContext(context: ExecutionContext)

case class SlickDatabaseWrapper (slickDatabase: SlickDatabase, masterSlave: Database.DBMasterSlave)

object Database {
  sealed trait DBMasterSlave
  case object Master extends DBMasterSlave
  case object Slave extends DBMasterSlave

  private[slick] val tryAgain = Failure(new Exception("try again! (this is not a real exception)"))
}

class Database @Inject() (
    val db: DataBaseComponent,
    val dbExecutionContext: DbExecutionContext,
    val sessionProvider: SlickSessionProvider,
    val playMode: Mode
  ) extends Logging {

  import DBSession._
  import Database._

  implicit val executionContext = dbExecutionContext.context

  val dialect: DatabaseDialect[_] = db.dialect

  def enteringSession[T](f: => T) = {
    if (DatabaseSessionLock.inSession.value) {
      val message = "already in a DB session!"
      //log.warn("Already in a DB session!", new InSessionException(message)) // todo(Andrew): re-enable
      //throw new InSessionException("already in a DB session!")
    }
    DatabaseSessionLock.inSession.withValue(true) { f }
  }

  def readOnlyAsync[T](f: ROSession => T)(implicit dbMasterSlave: DBMasterSlave = Master): Future[T] = future { readOnly(dbMasterSlave)(f) }
  def readOnlyAsync[T](dbMasterSlave: DBMasterSlave = Master)(f: ROSession => T): Future[T] = future { readOnly(dbMasterSlave)(f) }
  def readWriteAsync[T](f: RWSession => T): Future[T] = future { readWrite(f) }
  def readWriteAsync[T](attempts: Int)(f: RWSession => T): Future[T] = future { readWrite(attempts)(f) }

  def readOnly[T](f: ROSession => T)(implicit dbMasterSlave: DBMasterSlave = Master): T = readOnly(dbMasterSlave)(f)

  private def resolveDb(dbMasterSlave: DBMasterSlave) = dbMasterSlave match {
    case Master =>
      SlickDatabaseWrapper(db.masterDb, Master)
    case Slave =>
      db.slaveDb match {
        case None =>
          SlickDatabaseWrapper(db.masterDb, Master)
        case Some(handle) =>
          log.info(s"session using Slave db")
          SlickDatabaseWrapper(handle, Slave)
      }
  }

  def readOnly[T](dbMasterSlave: DBMasterSlave = Master)(f: ROSession => T): T = enteringSession {
    val ro = new ROSession(dbMasterSlave, {
      val handle = resolveDb(dbMasterSlave)
      Statsd.increment(s"db.read.${handle.masterSlave}")
      sessionProvider.createReadOnlySession(handle.slickDatabase)
    })
    try f(ro) finally ro.close()
  }

  def readOnly[T](attempts: Int, dbMasterSlave: DBMasterSlave = Master)(f: ROSession => T): T = enteringSession { // retry by default with implicit override?
    1 to attempts - 1 foreach { attempt =>
      try {
        return readOnly(f)
      } catch {
        case ex: MySQLIntegrityConstraintViolationException =>
          throw ex
        case t: SQLException =>
          val throwableName = t.getClass.getSimpleName
          log.warn(s"Failed ($throwableName) readOnly transaction attempt $attempt of $attempts")
          Statsd.increment(s"db.fail.attempt.$attempt.$throwableName")
      }
    }
    readOnly(f)
  }

  private def createReadWriteSession = new RWSession({//always master
    Statsd.increment("db.write.Master")
    sessionProvider.createReadWriteSession(db.masterDb)
  })

  def readWrite[T](f: RWSession => T): T = enteringSession {
    val rw = createReadWriteSession
    try rw.withTransaction { f(rw) } finally rw.close()
  }

  def readWrite[T](attempts: Int)(f: RWSession => T): T = {
    1 to attempts - 1 foreach { attempt =>
      try {
        return readWrite(f)
      } catch {
        case ex: MySQLIntegrityConstraintViolationException =>
          throw ex
        case t: SQLException =>
          val throwableName = t.getClass.getSimpleName
          log.warn(s"Failed ($throwableName) readWrite transaction attempt $attempt of $attempts")
          Statsd.increment(s"db.fail.attempt.$attempt.$throwableName")
      }
    }
    readWrite(f)
  }

  def readWriteBatch[D, T](batch: Seq[D])(f: (RWSession, D) => T): Map[D, Try[T]] = {
    val rw = createReadWriteSession
    try {
      var successCnt = 0
      var failure: Failure[T] = null
      val results = batch.foldLeft(Map.empty[D, Try[T]]) { (results, item) =>
        val oneResult = if (failure == null) {
          Try(rw.withTransaction { f(rw, item) }) match {
            case s: Success[T] => successCnt += 1; s
            case f: Failure[T] => failure = f; f
          }
        } else {
          Database.tryAgain
        }
        results + (item -> oneResult)
      }
      if (failure != null)
        log.warn(s"Failed ({fail.exception.getClass.getSimpleName}) readWrite transaction, processed ${successCnt} out of ${batch.size}")

      results
    } finally { rw.close() }
  }

  def readWriteBatch[D, T](batch: Seq[D], attempts: Int)(f: (RWSession, D) => T): Map[D, Try[T]] = {
    var results = Map.empty[D, Try[T]]
    var pending = batch
    1 to attempts - 1 foreach { attempt =>
      val partialResults = readWriteBatch(pending)(f)
      results ++= partialResults
      pending = partialResults.keys.filter{ d =>
        partialResults(d) match {
          case Failure(e: MySQLIntegrityConstraintViolationException) => false // do not retry if an integrity constraint violation occurred for this item
          case Failure(e: SQLException) => true                                // retry for other SQLException
          case _ => false                                                      // no retry for all other cases
        }
      }.toSeq
      if (pending.isEmpty) return results
    }
    val partialResults = readWriteBatch(pending)(f)
    results ++ partialResults
  }
}

