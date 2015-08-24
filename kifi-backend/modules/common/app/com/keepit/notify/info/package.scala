package com.keepit.notify

import com.keepit.notify.model.event.NotificationEvent

package object info {

  type NotificationContext[N <: NotificationEvent] = (N, UsingDbSubset[NotificationInfo])

}
