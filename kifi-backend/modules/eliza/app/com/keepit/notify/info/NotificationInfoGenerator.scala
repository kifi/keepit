package com.keepit.notify.info

import com.google.inject.Inject
import com.keepit.commanders.ProcessedImageSize
import com.keepit.common.db.Id
import com.keepit.eliza.model.{ NotificationWithInfo, NotificationWithItems, NotificationItem, Notification }
import com.keepit.model.{ Keep, Organization, Library }
import com.keepit.notify.model.NotificationKind
import com.keepit.notify.model.event.NotificationEvent
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ ExecutionContext, Future }

class NotificationInfoGenerator @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    notificationKindInfoRequests: NotificationKindInfoRequests,
    implicit val ec: ExecutionContext) {

  def generateInfo(notifs: Seq[NotificationWithItems]): Future[Seq[NotificationWithInfo]] = {
    val notifInfoRequests = notifs.map {
      case NotificationWithItems(notif, items) =>
        val infoRequest = notificationKindInfoRequests.requestsFor(notif, items)
        (notif, infoRequest)
    }.toMap

    val infoRequests = notifInfoRequests.map {
      case (notif, infoRequest) => infoRequest
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

    val externalUserIdsF = for {
      orgs <- orgsF
      keeps <- keepsF
    } yield (orgs.values.map(_.ownerId) ++ keeps.values.map(_.ownerId)).toSeq.distinct

    val userIdsFromExternalF = for {
      externalIds <- externalUserIdsF
      userIds <- shoeboxServiceClient.getUserIdsByExternalIds(externalIds)
    } yield userIds

    val batchedInfosF = for {
      libs <- libsF
      orgs <- orgsF
      keeps <- keepsF
      externalIds <- externalUserIdsF
      fromExternalIds <- userIdsFromExternalF
      userIds = (userRequests ++ fromExternalIds).toSeq.distinct
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
      notifs map {
        case NotificationWithItems(notif, items) =>
          val infoRequest = notifInfoRequests(notif)
          val info = infoRequest.fn(batchedInfos)
          NotificationWithInfo(notif, items, info)
      }
    }
  }

}
