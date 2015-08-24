package com.keepit.notify

import com.keepit.notify.info.{ ShoeboxDbViewRequestHandlerImpl, DbViewRequestHandler }

case class ShoeboxNotifyModule() extends NotifyModule {

  override def configure(): Unit = {
    bind[DbViewRequestHandler].to[ShoeboxDbViewRequestHandlerImpl]
  }

}

