package com.keepit.payments

import com.keepit.common.db.slick.DBSession.RWSession

trait AccountEventTrackingCommander {
  def track[E](event: AccountEvent)(implicit session: RWSession): Unit
}


