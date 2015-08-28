package com.keepit.notify

import com.keepit.common.db.Id
import com.keepit.notify.model.event.NotificationEvent

package object info {

  type NotificationContext[N <: NotificationEvent] = (N, UsingDbView[NotificationInfo])

  type HasId[M] = { def id: Option[Id[M]] }

  type ExDbViewKey = DbViewKey[M, R] forSome { type M <: HasId[M]; type R}
  type ExDbViewRequest = DbViewRequest[M, R] forSome { type M <: HasId[M]; type R}

  type ExDbViewResult = Map[Id[M], R] forSome { type M <: HasId[M]; type R}

}
