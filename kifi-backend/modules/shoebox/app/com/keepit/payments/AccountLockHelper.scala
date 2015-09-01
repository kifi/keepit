package com.keepit.payments

import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id
import com.keepit.model.Organization
import com.keepit.common.db.slick.DBSession.RWSession

import com.google.inject.Inject

class AccountLockHelper @Inject() (db: Database, paidAccountRepo: PaidAccountRepo) {

  def maybeSessionWithAccountLock[T](orgId: Id[Organization])(f: RWSession => T): Option[T] = db.readWrite { implicit session =>
    val hasLock = try {
      paidAccountRepo.tryGetAccountLock(orgId)
    } catch {
      case ex: org.h2.jdbc.JdbcSQLException if ex.getErrorCode() == 50200 => false //H2 lock aquisition timeout
      case ex: java.sql.SQLException if ex.getErrorCode() == 1205 => false //MySQL lock aquisition timeout
    }
    if (hasLock) {
      try {
        Some(f(session))
      } finally {
        paidAccountRepo.releaseAccountLock(orgId)
      }
    } else {
      None
    }
  }

}

