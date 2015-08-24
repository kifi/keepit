package com.keepit.notify

import com.google.inject.{ Singleton, Provides }
import com.keepit.inject.AppScoped
import com.keepit.notify.info.{ ElizaNotificationInfoSourceImpl, NotificationInfoSource }

case class ElizaNotifyModule() extends NotifyModule {

  override def configure(): Unit = {
    bind[NotificationInfoSource].to[ElizaNotificationInfoSourceImpl]
  }

}
