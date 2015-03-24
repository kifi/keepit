package com.keepit.common.db.slick

import scala.slick.jdbc.JdbcBackend.{ Database => SlickDatabase }
import scala.slick.jdbc.JdbcBackend.Session
import scala.slick.jdbc.ResultSetConcurrency

import scala.concurrent._
import scala.util.DynamicVariable
import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.db.DatabaseDialect
import com.keepit.common.logging.Logging
import com.keepit.macros.Location
import java.sql.SQLException
import play.api.Mode.Mode
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

case class SlickDatabaseWrapper(slickDatabase: SlickDatabase, masterReplica: Database.DBMasterReplica)

class ExecutionSkipped extends Exception("skipped. try again! (this is not a real exception)")

object Database {
  private[slick] sealed trait DBMasterReplica
  private[slick] case object Master extends DBMasterReplica
  private[slick] case object Replica extends DBMasterReplica

  private[slick] val executionSkipped = Failure(new ExecutionSkipped)
}

class Database @Inject() (
    val db: DataBaseComponent,
    val dbExecutionContext: DbExecutionContext,
    val sessionProvider: SlickSessionProvider,
    val playMode: Mode) extends Logging {

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

  def readOnlyMasterAsync[T](f: ROSession => T)(implicit location: Location): Future[T] = Future { readOnlyOneAttempt(Master)(f)(location) }
  def readOnlyReplicaAsync[T](f: ROSession => T)(implicit location: Location): Future[T] = Future { readOnlyOneAttempt(Replica)(f)(location) }

  def readWriteAsync[T](f: RWSession => T)(implicit location: Location): Future[T] = Future { readWrite(f)(location) }
  def readWriteAsync[T](attempts: Int)(f: RWSession => T)(implicit location: Location): Future[T] = Future { readWrite(attempts)(f)(location) }

  def readOnlyMaster[T](f: ROSession => T)(implicit location: Location): T = readOnlyOneAttempt(Master)(f)(location)
  def readOnlyMaster[T](attempts: Int)(f: ROSession => T)(implicit location: Location): T = readOnlyWithAttempts(attempts, Master)(f)(location)

  def readOnlyReplica[T](f: ROSession => T)(implicit location: Location): T = readOnlyOneAttempt(Replica)(f)(location)
  def readOnlyReplica[T](attempts: Int)(f: ROSession => T)(implicit location: Location): T = readOnlyWithAttempts(attempts, Replica)(f)(location)

  private def readOnly0[T](f: ROSession => T, dbMasterReplica: DBMasterReplica, location: Location): T = readOnlyOneAttempt(dbMasterReplica)(f)(location)

  private def resolveDb(dbMasterReplica: DBMasterReplica) = dbMasterReplica match {
    case Master =>
      SlickDatabaseWrapper(db.masterDb, Master)
    case Replica =>
      db.replicaDb match {
        case None =>
          SlickDatabaseWrapper(db.masterDb, Master)
        case Some(handle) =>
          SlickDatabaseWrapper(handle, Replica)
      }
  }

  private def readOnlyOneAttempt[T](dbMasterReplica: DBMasterReplica = Master)(f: ROSession => T)(implicit location: Location): T = enteringSession {
    val ro = new ROSession(dbMasterReplica, {
      val handle = resolveDb(dbMasterReplica)
      sessionProvider.createReadOnlySession(handle.slickDatabase)
    }, location)
    try f(ro) finally ro.close()
  }

  private def readOnlyWithAttempts[T](attempts: Int, dbMasterReplica: DBMasterReplica = Master)(f: ROSession => T)(implicit location: Location): T = enteringSession { // retry by default with implicit override?
    1 to attempts - 1 foreach { attempt =>
      try {
        return readOnly0(f, dbMasterReplica, location)
      } catch {
        case t: SQLException =>
          val throwableName = t.getClass.getSimpleName
          log.warn(s"Failed ($throwableName) readOnly transaction attempt $attempt of $attempts")
          statsd.incrementOne(s"db.fail.attempt.$attempt.$throwableName", ALWAYS)
      }
    }
    readOnly0(f, dbMasterReplica, location)
  }

  private def createReadWriteSession(location: Location) = new RWSession({ //always master
    sessionProvider.createReadWriteSession(db.masterDb)
  }, location)

  def readWrite[T](f: RWSession => T)(implicit location: Location): T = enteringSession {
    val rw = createReadWriteSession(location)
    try rw.withTransaction { f(rw) } finally rw.close()
  }

  def readWriteWithAutocommit[T](f: RWSession => T)(implicit location: Location): T = enteringSession {
    val rw = createReadWriteSession(location)
    try rw.withAutocommit { f(rw) } finally rw.close()
  }

  def readWrite[T](attempts: Int)(f: RWSession => T)(implicit location: Location): T = {
    1 to attempts - 1 foreach { attempt =>
      try {
        return readWrite(f)(location)
      } catch {
        case t: SQLException =>
          val throwableName = t.getClass.getSimpleName
          log.error(s"Failed ($throwableName) readWrite transaction attempt $attempt of $attempts: $t", t)
          statsd.incrementOne(s"db.fail.attempt.$attempt.$throwableName", ALWAYS)
      }
    }
    readWrite(f)(location)
  }

  private[this] val READ_WRITE_BATCH_SESSION_REFRESH_INTERVAL = 500
  private[this] val READ_WRITE_SEQ_SESSION_REFRESH_INTERVAL = 50

  def readWriteSeq[D, T](batch: Seq[D])(f: (RWSession, D) => T)(implicit location: Location): Unit = {
    def sink(a: D, b: T): Unit = {}
    readWriteSeq(batch, sink)(f)(location)
  }

  def readWriteSeq[D, T](batch: Seq[D], collector: (D, T) => Unit)(f: (RWSession, D) => T)(implicit location: Location): Unit = {
    batch.grouped(READ_WRITE_SEQ_SESSION_REFRESH_INTERVAL).foreach { chunk =>
      val rw = createReadWriteSession(location)
      try {
        chunk.foreach { item => collector(item, rw.withTransaction { f(rw, item) }) }
      } finally { rw.close() }
    }
  }

  def readWriteBatch[D, T](batch: Seq[D])(f: (RWSession, D) => T)(implicit location: Location): Map[D, Try[T]] = {
    var successCnt = 0
    var failure: Failure[T] = null

    val results = batch.grouped(READ_WRITE_BATCH_SESSION_REFRESH_INTERVAL).foldLeft(Map.empty[D, Try[T]]) { (results, chunk) =>
      val rw = createReadWriteSession(location)
      try {
        results ++ chunk.map { item =>
          val oneResult = if (failure == null) {
            Try(rw.withTransaction { f(rw, item) }) match {
              case s: Success[T] =>
                successCnt += 1; s
              case f: Failure[T] => failure = f; f
            }
          } else {
            Database.executionSkipped
          }
          (item -> oneResult)
        }
      } finally { rw.close() }
    }
    if (failure != null) log.warn(s"Failed (${failure.exception.getClass.getSimpleName}) readWrite transaction, processed $successCnt out of ${batch.size}")
    results
  }

  def readWriteBatch[D, T](batch: Seq[D], attempts: Int)(f: (RWSession, D) => T)(implicit location: Location): Map[D, Try[T]] = {
    var results = Map.empty[D, Try[T]]
    var pending = batch
    1 to attempts - 1 foreach { attempt =>
      val partialResults = readWriteBatch(pending)(f)(location)
      results ++= partialResults
      pending = batch.filter { d =>
        results(d) match {
          case Failure(e: SQLException) => true // retry for other SQLException
          case Failure(e: ExecutionSkipped) => true // retry skipped items
          case _ => false // no retry for all other cases
        }
      }.toSeq
      if (pending.isEmpty) return results
    }
    val partialResults = readWriteBatch(pending)(f)(location)
    results ++ partialResults
  }
}

