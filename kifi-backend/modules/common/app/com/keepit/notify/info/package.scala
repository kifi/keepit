package com.keepit.notify

import com.keepit.notify.model.NotificationEvent

package object info {

  type NotificationContext[N <: NotificationEvent] = (N, UsingDbSubset[NotificationInfo])

}
