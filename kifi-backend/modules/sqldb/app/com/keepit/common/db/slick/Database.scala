package com.keepit.common.db.slick

import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.Mode.Mode
import play.api.{ Logger, Mode, Play }

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

private object DatabaseSessionLock {
  val tl = new ThreadLocal[Boolean]
  sealed trait SourceState
  case object ReadSession extends SourceState
  case object WriteSession extends SourceState
  case object NoSession extends SourceState
}

// this allows us to replace the database session implementation in tests and check when sessions are being obtained
@ImplementedBy(classOf[SlickSessionProviderImpl])
trait SlickSessionProvider {
  def createReadOnlySession(handle: SlickDatabase): Session
  def createReadWriteSession(handle: SlickDatabase): Session
}

@Singleton
class SlickSessionProviderImpl extends SlickSessionProvider {
  import com.keepit.common.db.slick.DBSession.ROSession

  private class SessionWrapper(session: Session, onClose: => Unit) extends Session {
    override def resultSetType = session.resultSetType
    override def resultSetConcurrency = session.resultSetConcurrency
    override def resultSetHoldability = session.resultSetHoldability
    def database = session.database
    def conn = session.conn
    def metaData = session.metaData
    def capabilities = session.capabilities
    def close() = onClose
    def rollback() = session.rollback()
    def withTransaction[T](f: => T) = session.withTransaction(f)
  }

  // Using two, so we can save a reference to both kinds
  private val existingROSession = new ThreadLocal[Session]
  private val existingRWSession = new ThreadLocal[Session]

  def createReadOnlySession(handle: SlickDatabase): Session = {
    val existingOpt = Option(existingROSession.get).orElse(Option(existingRWSession.get))
    existingOpt match {
      case Some(existing) =>
        new SessionWrapper(existing, {
          // Do nothing. If working on reducing these, feel free to add logs.
        })
      case None => // This is the expected/good case.
        val rawSession = handle.createSession().forParameters(rsConcurrency = ResultSetConcurrency.ReadOnly)
        val newSession = new SessionWrapper(rawSession, {
          existingROSession.set(null)
          rawSession.close()
        })
        existingROSession.set(newSession)
        newSession
    }
  }
  def createReadWriteSession(handle: SlickDatabase): Session = {
    val existingOpt = Option(existingRWSession.get)
    existingOpt match {
      case Some(existing) =>
        new SessionWrapper(existing, {
          existing.conn.commit()
          // Do nothing else. If working on reducing these, feel free to add logs.
        })
      case None => // This is the expected/good case.
        val rawSession = handle.createSession().forParameters(rsConcurrency = ResultSetConcurrency.Updatable)
        val newSession = new SessionWrapper(rawSession, {
          existingRWSession.set(null)
          rawSession.close()
        })
        existingRWSession.set(newSession)
        newSession
    }
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
    val playMode: Mode,
    airbrake: AirbrakeNotifier) extends Logging {

  import DBSession._
  import Database._

  implicit val executionContext = dbExecutionContext.context

  val dialect: DatabaseDialect[_] = db.dialect

  def enteringSession[T](f: => T) = {
    val detectLayeredSessions = false && !Play.maybeApplication.exists(_.mode == Mode.Prod) // Remove `false &&` to enable this
    if (detectLayeredSessions) {
      val wasInSession = Option(DatabaseSessionLock.tl.get).getOrElse(false)
      val verbose = true
      if (wasInSession) {
        import DatabaseSessionLock._
        var sourceState: SourceState = NoSession
        val databaseSources = Set("Database.scala", "DBSession.scala")
        val stack = new InSessionException("").getStackTrace.filter(_.getClassName.contains("com.keepit")).flatMap { l =>
          val testCreatedTheSessionTag = if (l.getFileName.endsWith("Test.scala")) " \u001b[34m(in test)\u001b[0m" else ""
          if (sourceState == WriteSession && !databaseSources.contains(l.getFileName)) {
            sourceState = NoSession
            Some(l.getFileName + ":" + l.getLineNumber + " \u001b[33;1mWRITE\u001b[0m" + testCreatedTheSessionTag)
          } else if (sourceState == ReadSession && !databaseSources.contains(l.getFileName)) {
            sourceState = NoSession
            Some(l.getFileName + ":" + l.getLineNumber + testCreatedTheSessionTag)
          } else if (l.getFileName == "Database.scala") {
            if (l.getMethodName.contains("readWrite")) {
              sourceState = WriteSession
            } else if (l.getMethodName.contains("readOnly")) {
              sourceState = ReadSession
            }
            None
          } else {
            Some("\t" + l.getFileName + ":" + l.getLineNumber)
          }
        }
        println("\uD83D\uDEA6  \u001b[31;4mMultiple sessions created:\u001b[0m\n\t" + stack.mkString("\n\t"))
      }
      DatabaseSessionLock.tl.set(true)
      try f finally DatabaseSessionLock.tl.set(wasInSession)
    } else {
      // In production and when not trying to detect layered sessions:
      f
    }
  }

  def readOnlyMasterAsync[T](f: ROSession => T)(implicit location: Location): Future[T] = Future { readOnlyOneAttempt(Master)(f)(location) }
  def readOnlyReplicaAsync[T](f: ROSession => T)(implicit location: Location): Future[T] = Future { readOnlyOneAttempt(Replica)(f)(location) }

  def readWriteAsync[T](f: RWSession => T)(implicit location: Location): Future[T] = Future { readWrite(f)(location) }
  def readWriteAsync[T](attempts: Int)(f: RWSession => T)(implicit location: Location): Future[T] = Future { readWrite(attempts)(f)(location) }

  def readOnlyMaster[T](f: ROSession => T)(implicit location: Location): T = readOnlyOneAttempt(Master)(f)(location)
  def readOnlyMaster[T](attempts: Int)(f: ROSession => T)(implicit location: Location): T = readOnlyWithAttempts(attempts, Master)(f)(location)

  def readOnlyReplica[T](f: ROSession => T)(implicit location: Location): T = readOnlyOneAttempt(Replica)(f)(location)
  def readOnlyReplica[T](attempts: Int)(f: ROSession => T)(implicit location: Location): T = readOnlyWithAttempts(attempts, Replica)(f)(location)

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

  private def readOnlyWithAttempts[T](attempts: Int, dbMasterReplica: DBMasterReplica)(f: ROSession => T)(location: Location): T = { // retry by default with implicit override?
    1 to attempts - 1 foreach { attempt =>
      try {
        return readOnlyOneAttempt(dbMasterReplica)(f)(location)
      } catch {
        case t: SQLException =>
          val throwableName = t.getClass.getSimpleName
          log.warn(s"Failed ($throwableName) readOnly transaction attempt $attempt of $attempts")
          statsd.incrementOne(s"db.fail.attempt.$attempt.$throwableName", ALWAYS)
      }
    }
    readOnlyOneAttempt(dbMasterReplica)(f)(location)
  }

  private def readOnlyOneAttempt[T](dbMasterReplica: DBMasterReplica = Master)(f: ROSession => T)(implicit location: Location): T = enteringSession {
    val ro = new ROSession(dbMasterReplica, {
      val handle = resolveDb(dbMasterReplica)
      sessionProvider.createReadOnlySession(handle.slickDatabase)
    }, location)
    try f(ro) finally ro.close()
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
              case f: Failure[T] =>
                failure = f
                airbrake.notify(s"Batch Chunk Failed (${failure.exception.getClass.getSimpleName}) readWrite transaction, processed item $item out of ${batch.size} items", failure.exception)
                f
            }
          } else {
            Database.executionSkipped
          }
          item -> oneResult
        }
      } finally { rw.close() }
    }
    if (failure != null) {
      //this is very dangerous since we swallow the exception (just report it) and don't let the caller know something bad happened as an exception
      //the signature of the method should be revisited to enforce check of partial failure of the transaction
      airbrake.notify(s"Batch partial fail (${failure.exception.getClass.getSimpleName}) readWrite transaction, processed $successCnt out of ${batch.size}", failure.exception)
    }
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

