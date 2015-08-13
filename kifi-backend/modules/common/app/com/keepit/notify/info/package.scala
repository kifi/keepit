package com.keepit.notify

import com.keepit.notify.info.NotificationInfo

package object info {

  type ReturnsInfoResult = ReturnsInfo[NotificationInfo]

  type Args = Map[String, Any]

}
