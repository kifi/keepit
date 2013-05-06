package com.keepit.common.db.slick

import scala.concurrent._
import scala.slick.session.ResultSetConcurrency
import scala.slick.session.Session
import scala.slick.session.{Database => SlickDatabase}
import scala.util.DynamicVariable

import com.google.inject.{Singleton, ImplementedBy, Inject, Provider}
import com.keepit.common.db.DatabaseDialect
import com.keepit.common.healthcheck._
import com.keepit.common.logging.Logging
import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException

import akka.actor.ActorSystem
import play.api.Mode.Mode
import play.api.Mode.Test

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

class Database @Inject() (
    val db: DataBaseComponent,
    val system: ActorSystem,
    val healthcheckPlugin: Provider[HealthcheckPlugin],
    val sessionProvider: SlickSessionProvider,
    val playMode: Mode
  ) extends Logging {

  import DBSession._

  implicit val executionContext = system.dispatchers.lookup("db-thread-pool-dispatcher")

  val dialect: DatabaseDialect[_] = db.dialect

  def enteringSession[T](f: => T) = {
    if (DatabaseSessionLock.inSession.value) {
      val message = "already in a DB session!"
      //healthcheckPlugin.get.addError(HealthcheckError(Some(new InSessionException(message)), None, None, Healthcheck.INTERNAL, Some(message)))
      if (playMode == Test) throw new InSessionException("already in a DB session!")
    }
    DatabaseSessionLock.inSession.withValue(true) { f }
  }

  def readOnlyAsync[T](f: ROSession => T): Future[T] = future { readOnly(f) }
  def readWriteAsync[T](f: RWSession => T): Future[T] = future { readWrite(f) }
  def readWriteAsync[T](attempts: Int)(f: RWSession => T): Future[T] = future { readWrite(attempts)(f) }

  def readOnly[T](f: ROSession => T): T = enteringSession {
    var initialized = false
    lazy val s = {
      initialized = true
      sessionProvider.createReadOnlySession(db.handle)
    }
    try f(new ROSession(s)) finally if (initialized) s.close()
  }

  def readWrite[T](f: RWSession => T): T = enteringSession {
    val s = sessionProvider.createReadWriteSession(db.handle)
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

