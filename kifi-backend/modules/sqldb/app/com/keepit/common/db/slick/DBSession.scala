package com.keepit.common.db.slick

import java.sql._
import scala.collection.mutable
import scala.concurrent._
import scala.util.{ Success, Failure, Try }
import play.api.Logger
import com.keepit.common.time._
import com.keepit.common.cache.TransactionalCaching
import com.keepit.common.logging.Logging
import scala.slick.jdbc.{ ResultSetConcurrency, ResultSetType, ResultSetHoldability }
import scala.slick.jdbc.JdbcBackend.{ Session, SessionDef }
import com.keepit.common.util.TrackingId
import com.keepit.macros.Location
import com.keepit.common.db.slick.Database.Master

object DBSession {
  abstract class SessionWrapper(val name: String, val masterSlave: Database.DBMasterReplica, _session: => Session, location: Location) extends SessionDef with Logging with TransactionalCaching {
    def database = _session.database
    private var open = false
    private var wasOpened = false
    private var doRollback = false
    private var transaction: Option[Promise[Unit]] = None
    private var transactionFuture: Option[Future[Unit]] = None
    private var startTime: Long = -1
    lazy val session = {
      val s = _session
      if (inTransaction) s.conn.setAutoCommit(false)
      open = true
      wasOpened = true
      startTime = System.currentTimeMillis
      s
    }
    lazy val clock = new SystemClock
    val sessionId = TrackingId.get
    val isReadOnly = masterSlave != Master
    def runningTime(): Long = System.currentTimeMillis - startTime
    private def timeCheck(): Unit = {
      val t = runningTime()
      if (t > 5000) { // tweak
        val msg = s"DBSession($sessionId,$name,$masterSlave) takes too long: $t ms"
        log.error(msg, new IllegalStateException(msg))
      }
    }

    private val dbLog = Logger("com.keepit.db")
    def conn: Connection = new DBConnectionWrapper(session.conn, dbLog, clock, masterSlave, sessionId)
    def metaData = session.metaData
    def capabilities = session.capabilities
    override def resultSetType = session.resultSetType
    override def resultSetConcurrency = session.resultSetConcurrency
    override def resultSetHoldability = session.resultSetHoldability

    def close(): Unit = synchronized {
      if (open) {
        session.close()
        val time = System.currentTimeMillis - startTime
        dbLog.info(s"t:${clock.now}\tsessionId:$sessionId\tdb:$masterSlave\ttype:SESSION\tduration:${time}\tname:$name\tlocation:${location.location}")
        timeCheck()
        open = false
      } else if (wasOpened) {
        log.warn(s"Closing the same connection more than once!\tlocation:${location.location}")
      }
    }

    def rollback() { doRollback = true }
    def inTransaction = transaction.isDefined

    private def requireTransaction = require(inTransaction, "Not in a transaction.")
    def onTransactionSuccess[U](f: => U)(implicit executor: ExecutionContext): Unit = {
      requireTransaction
      transactionFuture = transactionFuture.map(_.andThen { case Success(_) => f })
    }
    def onTransactionFailure[U](f: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Unit = {
      requireTransaction
      transactionFuture = transactionFuture.map(_.andThen { case Failure(throwable) => f(throwable) })
    }
    def onTransactionComplete[U](f: Function[Try[Unit], U])(implicit executor: ExecutionContext): Unit = {
      requireTransaction
      transactionFuture = transactionFuture.map(_.andThen { case tryU => f(tryU) })
    }

    def withTransaction[T](f: => T): T = if (inTransaction) f else {
      if (open) conn.setAutoCommit(false)
      transaction = Some(Promise())
      transactionFuture = transaction.map(_.future)
      beginCacheTransaction()
      try {
        var done = false
        try {
          val res = f
          done = true
          res
        } finally {
          if (open && !done || doRollback) {
            conn.rollback()
            rollbackCacheTransaction()
            transaction.get.failure(new Exception("Transaction was rolled back."))
          } else if (open) {
            conn.commit()
            commitCacheTransaction()
            transaction.get.success()
          }
        }
      } finally {
        if (open) conn.setAutoCommit(true)
        transaction = None
        transactionFuture = None
      }
    }

    def withAutocommit[T](f: => T): T = {
      if (open) conn.setAutoCommit(true)
      f
    }

    private val statementCache = new mutable.HashMap[String, PreparedStatement]
    def getPreparedStatement(statement: String): PreparedStatement = {
      val preparedStatement = statementCache.getOrElseUpdate(statement, {
        val newPreparedStatement = this.conn.prepareStatement(statement)
        newPreparedStatement
      })
      dbLog.info(s"t:${clock.now}\tsessionId:$sessionId\tdb:$masterSlave\ttype:USE_PRP_STMT\tstatement:$statement")
      preparedStatement
    }

    override def forParameters(rsType: ResultSetType = resultSetType, rsConcurrency: ResultSetConcurrency = resultSetConcurrency,
      rsHoldability: ResultSetHoldability = resultSetHoldability) =
      _session.forParameters(rsType, rsConcurrency, rsHoldability)
  }

  abstract class RSession(name: String, masterSlave: Database.DBMasterReplica, roSession: => Session, location: Location) extends SessionWrapper(name, masterSlave, roSession, location)
  class ROSession(masterSlave: Database.DBMasterReplica, roSession: => Session, location: Location) extends RSession("RO", masterSlave, roSession, location)
  class RWSession(rwSession: => Session, location: Location) extends RSession("RW", Database.Master, rwSession, location) //RWSession is always reading from master
  //
  //  implicit def roToSession(roSession: ROSession): Session = roSession.session
  //  implicit def rwToSession(rwSession: RWSession): Session = rwSession.session
}
