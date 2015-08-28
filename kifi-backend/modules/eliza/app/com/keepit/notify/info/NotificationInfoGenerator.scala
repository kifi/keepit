package com.keepit.notify.info

import com.google.inject.Inject
import com.keepit.notify.model.event.NotificationEvent
import com.keepit.shoebox.ShoeboxServiceClient

class NotificationInfoGenerator @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient) {

  def forEvents(events: Seq[NotificationEvent])

}
