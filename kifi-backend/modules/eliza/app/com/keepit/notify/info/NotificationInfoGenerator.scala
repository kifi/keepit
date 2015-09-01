package com.keepit.notify.info

import com.google.inject.Inject
import com.keepit.commanders.ProcessedImageSize
import com.keepit.common.db.Id
import com.keepit.eliza.model.{ NotificationItem, Notification }
import com.keepit.model.{ Keep, Organization, Library }
import com.keepit.notify.model.NotificationKind
import com.keepit.notify.model.event.NotificationEvent
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ ExecutionContext, Future }

class NotificationInfoGenerator @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    implicit val ec: ExecutionContext) {

  private def genericKind(notif: Notification) = {
    // the notification and items don't know that they have the same kind
    notif.kind.asInstanceOf[NotificationKind[NotificationEvent]]
  }

  def generateInfo(notifs: Seq[(Notification, Set[NotificationItem])]): Future[Seq[(Notification, Set[NotificationItem], NotificationInfo)]] = {
    val notifInfoRequests = notifs map {
      case (notif, items) =>
        val kind = genericKind(notif)
        val infoRequest = kind.info(items.map(_.event))
        (notif, items, infoRequest)
    }

    val infoRequests = notifInfoRequests map {
      case (_, _, infoRequest) => infoRequest
    }

    val libRequests = infoRequests.flatMap { infoRequest =>
      infoRequest.requests.collect {
        case r: NotificationInfoRequest.RequestLibrary => r.id
      }
    }.toSet
    val orgRequests = infoRequests.flatMap { infoRequest =>
      infoRequest.requests.collect {
        case r: NotificationInfoRequest.RequestOrganization => r.id
      }
    }.toSet
    val keepRequests = infoRequests.flatMap { infoRequest =>
      infoRequest.requests.collect {
        case r: NotificationInfoRequest.RequestKeep => r.id
      }
    }.toSet
    val userRequests = infoRequests.flatMap { infoRequest =>
      infoRequest.requests.collect {
        case r: NotificationInfoRequest.RequestUser => r.id
      }
    }.toSet

    val libsF = shoeboxServiceClient.getBasicLibraryDetails(libRequests, ProcessedImageSize.Small.idealSize, None)
    val orgsF = shoeboxServiceClient.getBasicOrganizationsByIds(orgRequests)
    val keepsF = shoeboxServiceClient.getBasicKeepsByIds(keepRequests)

    val externalIdsF = for {
      orgs <- orgsF
      keeps <- keepsF
    } yield (orgs.values.map(_.ownerId) ++ keeps.values.map(_.ownerId)).toSeq.distinct

    val fromExternalIdsF = for {
      externalIds <- externalIdsF
      userIds <- shoeboxServiceClient.getUserIdsByExternalIds(externalIds)
    } yield userIds

    val batchedInfosF = for {
      libs <- libsF
      orgs <- orgsF
      keeps <- keepsF
      externalIds <- externalIdsF
      fromExternalIds <- fromExternalIdsF
      userIds = (userRequests ++ libs.values.map(_.ownerId) ++ fromExternalIds).toSeq.distinct
      users <- shoeboxServiceClient.getBasicUsers(userIds)
      usersExternal = externalIds.zip(fromExternalIds).map {
        case (externalId, id) => externalId -> users(id)
      }.toMap
    } yield {
      new BatchedNotificationInfos(
        users,
        usersExternal,
        libs,
        keeps,
        orgs
      )
    }

    for {
      batchedInfos <- batchedInfosF
    } yield {
      notifInfoRequests map {
        case (notif, items, request) =>
          val kind = genericKind(notif)
          val infoRequest = kind.info(items.map(_.event))
          (notif, items, infoRequest.fn(batchedInfos))
      }
    }
  }

}
