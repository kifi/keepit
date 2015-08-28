package com.keepit.notify

import com.keepit.common.db.Id
import com.keepit.notify.model.event.NotificationEvent

package object info {

  type HasId[M] = { def id: Option[Id[M]] }

  type ExInfoRequest = NotificationInfoRequest[M, R] forSome { type M <: HasId[M]; type R}

}
