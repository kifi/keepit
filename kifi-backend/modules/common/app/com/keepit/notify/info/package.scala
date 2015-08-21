package com.keepit.notify

import com.keepit.common.db.Id
import com.keepit.notify.model.event.NotificationEvent

package object info {

  type NotificationContext[N <: NotificationEvent] = (N, UsingDbView[NotificationInfo])

  type HasId[M] = { def id: Option[Id[M]] }

}
