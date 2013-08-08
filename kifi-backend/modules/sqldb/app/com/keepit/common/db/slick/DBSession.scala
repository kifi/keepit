package com.keepit.common.db.slick

import com.google.inject.{Inject, Provider}
import com.keepit.common.db.{ DbSequence, DatabaseDialect }
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

object DBSession {
  abstract class SessionWrapper(_session: => Session) extends Session {
    private var open = false
    private var doRollback = false
    private var inTransaction = false
    lazy val session = {
      val s = _session
      if (inTransaction) s.conn.setAutoCommit(false)
      open = true
      s
    }

    def conn: Connection = session.conn
    def metaData = session.metaData
    def capabilities = session.capabilities
    override def resultSetType = session.resultSetType
    override def resultSetConcurrency = session.resultSetConcurrency
    override def resultSetHoldability = session.resultSetHoldability
    def close() { if (open) session.close() }
    def rollback() { doRollback = true }

    def withTransaction[T](f: => T): T = if (inTransaction) f else {
      if (open) conn.setAutoCommit(false)
      inTransaction = true
      try {
        var done = false
        try {
          val res = f
          done = true
          res
        } finally {
          if (open && !done || doRollback) conn.rollback()
        }
      } finally {
        if (open) conn.setAutoCommit(true)
        inTransaction = false
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
