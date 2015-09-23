package com.keepit.notify

import com.keepit.notify.model.event.NotificationEvent

package object model {

  /**
   * Writing `NotificationKind[_ <: NotificationEvent, _]` again and again is not fun.
   */
  type NKind = NotificationKind[_ <: NotificationEvent, _]

}
