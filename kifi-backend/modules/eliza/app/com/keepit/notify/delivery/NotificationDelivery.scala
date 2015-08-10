package com.keepit.notify.delivery

import com.keepit.notify.model.NotificationEvent

trait NotificationDelivery {

  def deliver(events: Set[NotificationEvent]): Unit

}

object NotificationDelivery {

  def both(first: NotificationDelivery, second: NotificationDelivery) = new NotificationDelivery {
    override def deliver(events: Set[NotificationEvent]): Unit = {
      first.deliver(events)
      second.deliver(events)
    }
  }

}
