package com.keepit.notify

import com.keepit.notify.info.{ DbViewRequestHandlers, ShoeboxDbViewRequestHandlerImpl, DbViewRequestHandler }

case class FakeShoeboxNotifyModule() extends NotifyModule {
  override def configure(): Unit = {
    bind[DbViewRequestHandler].to[FakeShoeboxDbViewRequestHandlerImpl]
  }
}

class FakeShoeboxDbViewRequestHandlerImpl extends DbViewRequestHandler {
  override val handlers: DbViewRequestHandlers = DbViewRequestHandlers()
}
