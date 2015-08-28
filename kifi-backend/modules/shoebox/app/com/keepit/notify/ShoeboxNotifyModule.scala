package com.keepit.notify

import com.keepit.notify.info.{ ShoeboxNotificationInfoSourceImpl, NotificationInfoSource }

case class ShoeboxNotifyModule() extends NotifyModule {

  override def configure(): Unit = {
    bind[NotificationInfoSource].to[ShoeboxNotificationInfoSourceImpl]
  }

}

