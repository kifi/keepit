package com.keepit.notify.delivery

import com.google.inject.Inject
import com.keepit.notify.model.NotificationEvent

class WsNotificationDelivery @Inject() () extends NotificationDelivery {

  override def deliver(events: Set[NotificationEvent]): Unit = {

  }

}
