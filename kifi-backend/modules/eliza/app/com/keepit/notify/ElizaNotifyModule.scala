package com.keepit.notify

import com.google.inject.{ Singleton, Provides }
import com.keepit.inject.AppScoped
import com.keepit.notify.info.{ ElizaDbViewRequestHandlerImpl, DbViewRequestHandler }

case class ElizaNotifyModule() extends NotifyModule {

  override def configure(): Unit = {
    bind[DbViewRequestHandler].to[ElizaDbViewRequestHandlerImpl]
  }

}
