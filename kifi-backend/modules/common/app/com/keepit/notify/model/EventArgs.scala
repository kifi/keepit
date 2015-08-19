package com.keepit.notify.model

import com.keepit.notify.info.{NeedInfo$, Args}

case class EventArgs[E <: NotificationEvent](event: NotificationEvent, argsMap: Args = Map()) {

  def args(args: (String, Any)*) = copy(argsMap = this.argsMap ++ args)

  def get[A](arg: String) = argsMap.get(arg).get.asInstanceOf[A]

}

case class GenEventArgs[E <: NotificationEvent](names: Seq[String], fn: E => Seq[NeedInfo[Any]])

object GenEventArgs {

  def sequence[E <: NotificationEvent](args: Seq[GenEventArgs[E]]): GenEventArgs[E] =
    GenEventArgs(args.flatMap(_.names), e => args.flatMap(arg => arg.fn(e)))

}
