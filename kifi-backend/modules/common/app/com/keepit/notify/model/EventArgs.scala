package com.keepit.notify.model

import com.keepit.notify.info.Args

case class EventArgs(event: NotificationEvent, argsMap: Args = Map()) {

  def args(args: (String, Any)*) = copy(argsMap = this.argsMap ++ args)

}
