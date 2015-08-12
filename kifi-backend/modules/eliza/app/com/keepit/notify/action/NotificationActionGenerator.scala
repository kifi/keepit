package com.keepit.notify.action

import com.google.inject.{Singleton, Inject}
import com.keepit.common.db.Id
import com.keepit.eliza.model.Notification
import com.keepit.model.Library
import com.keepit.shoebox.ShoeboxServiceClient

@Singleton
class NotificationActionGenerator @Inject() (
  val shoeboxServiceClient: ShoeboxServiceClient
) {

  def viewLibrary(notification: Id[Notification], library: Id[Library]): ViewLibrary = {

  }

}
