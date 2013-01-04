package com.keepit.common.db.slick

import play.api.Play
import _root_.com.keepit.common.db.DbInfo

object Connection {
  def getHandle(dbInfo: DbInfo): DataBaseComponent = {
    dbInfo.driverName match {
      case MySQL.driverName     => new MySQL( dbInfo )
      case H2.driverName     => new H2( dbInfo )
    }
  }
}

