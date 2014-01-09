package com.keepit.common.db.slick

import java.sql._
import scala.collection.mutable
import scala.slick.session.{Session, ResultSetConcurrency, ResultSetType, ResultSetHoldability }
import scala.concurrent._
import scala.util.Try

import play.api.Logger
import com.keepit.common.time._
import scala.Some

object DBSession {
  abstract class SessionWrapper(val name: String, val masterSlave: Database.DBMasterSlave, _session: => Session) extends Session {
    private var open = false
    private var doRollback = false
    private var transaction: Option[Promise[Unit]] = None
    private var startTime: Long = -1
    lazy val session = {
      val s = _session
      if (inTransaction) s.conn.setAutoCommit(false)
      open = true
      startTime = System.currentTimeMillis
      s
    }
    lazy val clock = new SystemClock

    private def transactionFuture: Future[Unit] = {
      require(inTransaction, "Not in a transaction.")
      transaction.get.future
    }

    private val dbLog = Logger("com.keepit.db")
    def conn: Connection = new DBConnectionWrapper(session.conn, dbLog, clock, masterSlave)
    def metaData = session.metaData
    def capabilities = session.capabilities
    override def resultSetType = session.resultSetType
    override def resultSetConcurrency = session.resultSetConcurrency
    override def resultSetHoldability = session.resultSetHoldability

    def close(): Unit = if (open) {
      session.close()
      val time = System.currentTimeMillis - startTime
      dbLog.info(s"t:${clock.now}\tdb:$masterSlave\ttype:SESSION\tduration:${time}\tname:$name")
    }

    def rollback() { doRollback = true }
    def inTransaction = transaction.nonEmpty

    def onTransactionSuccess[U](f: => U)(implicit executor: ExecutionContext): Unit = transactionFuture.onSuccess { case _: Unit => f }
    def onTransactionFailure [U](f: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): Unit = transactionFuture.onFailure(f)
    def onTransactionComplete[U](f: Function[Try[Unit], U])(implicit executor: ExecutionContext): Unit = transactionFuture.onComplete(f)

    def withTransaction[T](f: => T): T = if (inTransaction) f else {
      if (open) conn.setAutoCommit(false)
      transaction = Some(Promise())
      try {
        var done = false
        try {
          val res = f
          done = true
          res
        } finally {
          if (open && !done || doRollback) {
            conn.rollback()
            transaction.get.failure(new Exception("Transaction was rolled back."))
          } else transaction.get.success()
        }
      } finally {
        if (open) conn.setAutoCommit(true)
        transaction = None
      }
    }

    private val statementCache = new mutable.HashMap[String, PreparedStatement]
    def getPreparedStatement(statement: String): PreparedStatement = {
      val preparedStatement = statementCache.getOrElseUpdate(statement, {
        val newPreparedStatement = this.conn.prepareStatement(statement)
        newPreparedStatement
      })
      dbLog.info(s"t:${clock.now}\tdb:$masterSlave\ttype:USE_PRP_STMT\tstatement:$statement")
      preparedStatement
    }

    override def forParameters(rsType: ResultSetType = resultSetType, rsConcurrency: ResultSetConcurrency = resultSetConcurrency,
      rsHoldability: ResultSetHoldability = resultSetHoldability) =
        _session.forParameters(rsType, rsConcurrency, rsHoldability)
  }

  abstract class RSession(name: String, masterSlave: Database.DBMasterSlave, roSession: => Session) extends SessionWrapper(name, masterSlave, roSession)
  class ROSession(masterSlave: Database.DBMasterSlave, roSession: => Session) extends RSession("RO", masterSlave, roSession)
  class RWSession(rwSession: => Session) extends RSession("RW", Database.Master, rwSession) //RWSession is always reading from master

  implicit def roToSession(roSession: ROSession): Session = roSession
  implicit def rwToSession(rwSession: RWSession): Session = rwSession
}
