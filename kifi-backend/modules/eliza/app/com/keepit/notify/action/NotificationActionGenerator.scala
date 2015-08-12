package com.keepit.notify.action

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.eliza.commanders.NotificationCommander
import com.keepit.eliza.model.{ NotificationItemRepo, Notification }
import com.keepit.model.Library
import com.keepit.shoebox.ShoeboxServiceClient

@Singleton
class NotificationActionGenerator @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    notificationCommander: NotificationCommander) {

  def viewLibrary(notification: Id[Notification], library: Id[Library]): ViewLibrary = {
    val items = notificationCommander.getItems(notification)
    null
  }

}
