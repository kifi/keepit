package com.keepit.notify

package object model {

  /**
   * Writing [[NotificationKind[_ <: NotificationEvent]] again and again is not fun.
   */
  type NKind = NotificationKind[_ <: NotificationEvent]

}
