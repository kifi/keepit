package com.keepit.notify.info

import com.google.inject.Inject
import com.keepit.commanders.ProcessedImageSize
import com.keepit.common.logging.Logging
import com.keepit.eliza.model.{ NotificationWithInfo, NotificationWithItems }
import com.keepit.notify.model.{ Recipient, UserRecipient }
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Try }

class NotificationInfoGenerator @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    notificationKindInfoRequests: NotificationKindInfoRequests,
    implicit val ec: ExecutionContext) extends Logging {

  def generateInfo(recipient: Recipient, notifs: Seq[NotificationWithItems]): Future[Seq[NotificationWithInfo]] = {
    val userIdOpt = recipient match {
      case UserRecipient(uid) => Some(uid)
      case _ => None
    }
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

    val libsF = shoeboxServiceClient.getLibraryCardInfos(libRequests, ProcessedImageSize.Small.idealSize, userIdOpt)
    val orgsF = shoeboxServiceClient.getBasicOrganizationsByIds(orgRequests)
    val keepsF = shoeboxServiceClient.getBasicKeepsByIds(keepRequests)

    val externalUserIdsF = for {
      orgs <- orgsF
      keeps <- keepsF
    } yield (orgs.values.map(_.ownerId) ++ keeps.values.map(_.ownerId)).toSet

    val userIdsFromExternalF = for {
      externalIds <- externalUserIdsF
      userIds <- shoeboxServiceClient.getUserIdsByExternalIdsNew(externalIds).map(_.values.toSet)
    } yield userIds

    val batchedInfosF = for {
      libs <- libsF
      orgs <- orgsF
      keeps <- keepsF
      externalIds <- externalUserIdsF
      fromExternalIds <- userIdsFromExternalF
      userIds = userRequests ++ fromExternalIds
      users <- shoeboxServiceClient.getBasicUsers(userIds.toSeq)
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
      notifs.flatMap {
        case NotificationWithItems(notif, items) =>
          val infoRequest = notifInfoRequests(notif)
          val infoTry = Try(infoRequest.fn(batchedInfos)) // TODO(ryan): can you write code that handles missing info elegantly instead of catching the inevitable KeyNotFoundException?
          infoTry match {
            case Failure(fail) => log.error(fail.toString)
            case _ =>
          }
          infoTry.toOption.map { info => NotificationWithInfo(notif, items, info) }
      }
    }
  }

}
