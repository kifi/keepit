package com.keepit.notify.info

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.eliza.model.{NotificationItem, Notification}
import com.keepit.model.{Keep, Organization, Library}
import com.keepit.notify.model.NotificationKind
import com.keepit.notify.model.event.NotificationEvent
import com.keepit.shoebox.ShoeboxServiceClient

class NotificationInfoGenerator @Inject() (
  shoeboxServiceClient: ShoeboxServiceClient
) {

  def generateInfo(notifs: Seq[(Notification, Set[NotificationItem])]) = {
    val infoRequests = notifs map {
      case (notif, items) =>
        val kind = items.head.kind
        // the notification and items don't know that they have the same kind
        val infoRequest = kind.asInstanceOf[NotificationKind[NotificationEvent]].info(items.map(_.event))
        infoRequest
    }

    val libRequests = infoRequests.flatMap { infoRequest =>
     infoRequest.requests.collect {
       case r: NotificationInfoRequest.RequestLibrary => r.id
     }
    }
    val orgRequests = infoRequests.flatMap { infoRequest =>
      infoRequest.requests.collect {
        case r: NotificationInfoRequest.RequestOrganization => r.id
      }
    }
    val keepRequests = infoRequests.flatMap { infoRequest =>
      infoRequest.requests.collect {
        case r: NotificationInfoRequest.RequestKeep => r.id
      }
    }
    val userIds = infoRequests.flatMap { infoRequest =>
      infoRequest.requests.collect {
        case r: NotificationInfoRequest.RequestUser=> r.id
      }
    }
  }

}
