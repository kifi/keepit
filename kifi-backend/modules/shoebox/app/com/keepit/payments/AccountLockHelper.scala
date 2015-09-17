package com.keepit.payments

import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id
import com.keepit.model.Organization
import com.keepit.common.db.slick.DBSession.RWSession

import com.google.inject.Inject

import scala.concurrent.{ Future, ExecutionContext }

class AccountLockHelper @Inject() (db: Database, paidAccountRepo: PaidAccountRepo, implicit val ec: ExecutionContext) {

  def acquireAccountLockForSession(orgId: Id[Organization], session: RWSession, attempts: Int = 1): Boolean = {
    val hasLock = try {
      paidAccountRepo.tryGetAccountLock(orgId)(session)
    } catch {
      case ex: org.h2.jdbc.JdbcSQLException if ex.getErrorCode() == 50200 => false //H2 lock aquisition timeout
      case ex: java.sql.SQLException if ex.getErrorCode() == 1205 => false //MySQL lock aquisition timeout
    }
    if (!hasLock && attempts > 1) {
      acquireAccountLockForSession(orgId, session, attempts - 1)
    } else {
      hasLock
    }
  }

  def releaseAccountLockForSession(orgId: Id[Organization], session: RWSession): Boolean = {
    paidAccountRepo.releaseAccountLock(orgId)(session)
  }

  def maybeSessionWithAccountLock[T](orgId: Id[Organization], attempts: Int = 1)(f: RWSession => T): Option[T] = db.readWrite { implicit session =>
    val hasLock = acquireAccountLockForSession(orgId, session, attempts)
    if (hasLock) {
      try {
        Some(f(session))
      } finally {
        releaseAccountLockForSession(orgId, session)
      }
    } else {
      None
    }
  }

  def maybeWithAccountLockAsync[T](orgId: Id[Organization], attempts: Int = 1)(f: => Future[T]): Option[Future[T]] = db.readWrite { implicit session =>
    val hasLock = db.readWrite { session => acquireAccountLockForSession(orgId, session, attempts) }
    if (hasLock) {
      try {
        val resFut = f
        resFut.onComplete { _ =>
          db.readWrite { session => releaseAccountLockForSession(orgId, session) }
        }
        Some(resFut)
      } catch {
        case t: Throwable => {
          db.readWrite { session => releaseAccountLockForSession(orgId, session) }
          throw t
        }
      }
    } else {
      None
    }
  }

}

