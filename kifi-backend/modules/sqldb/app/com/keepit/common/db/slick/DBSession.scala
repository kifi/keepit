package com.keepit.common.db.slick

import java.sql.{ PreparedStatement, Connection }
import scala.collection.mutable
import scala.slick.session.{Session, ResultSetConcurrency, ResultSetType, ResultSetHoldability }
import scala.concurrent._
import scala.util.Try

object DBSession {
  abstract class SessionWrapper(_session: => Session) extends Session {
    private var open = false
    private var doRollback = false
    private var transaction: Option[Promise[Unit]] = None
    lazy val session = {
      val s = _session
      if (inTransaction) s.conn.setAutoCommit(false)
      open = true
      s
    }

    private def transactionFuture: Future[Unit] = {
      require(inTransaction, "Not in a transaction.")
      transaction.get.future
    }

    def conn: Connection = session.conn
    def metaData = session.metaData
    def capabilities = session.capabilities
    override def resultSetType = session.resultSetType
    override def resultSetConcurrency = session.resultSetConcurrency
    override def resultSetHoldability = session.resultSetHoldability
    def close() { if (open) session.close() }
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
    def getPreparedStatement(statement: String): PreparedStatement =
      statementCache.getOrElseUpdate(statement, this.conn.prepareStatement(statement))

    override def forParameters(rsType: ResultSetType = resultSetType, rsConcurrency: ResultSetConcurrency = resultSetConcurrency,
      rsHoldability: ResultSetHoldability = resultSetHoldability) = throw new UnsupportedOperationException
  }

  abstract class RSession(roSession: => Session) extends SessionWrapper(roSession)
  class ROSession(roSession: => Session) extends RSession(roSession)
  class RWSession(rwSession: => Session) extends RSession(rwSession)

  implicit def roToSession(roSession: ROSession): Session = roSession.session
  implicit def rwToSession(rwSession: RWSession): Session = rwSession.session
}
