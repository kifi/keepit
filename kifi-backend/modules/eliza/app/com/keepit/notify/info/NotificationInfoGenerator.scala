package com.keepit.notify.info

import com.google.inject.Inject
import com.keepit.commanders.ProcessedImageSize
import com.keepit.common.core._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.eliza.commanders.NotificationCommander
import com.keepit.eliza.model.{ NotificationWithInfo, NotificationWithItems }
import com.keepit.notify.model.{ Recipient, UserRecipient }
import com.keepit.rover.RoverServiceClient
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Failure, Try }

class NotificationInfoGenerator @Inject() (
    notifCommander: NotificationCommander,
    shoeboxServiceClient: ShoeboxServiceClient,
    roverServiceClient: RoverServiceClient,
    notificationKindInfoRequests: NotificationKindInfoRequests,
    implicit val airbrake: AirbrakeNotifier,
    implicit val config: PublicIdConfiguration,
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
        case r: NotificationInfoRequest.RequestUriSummary => r.id // because `RequestUriSummary` needs basicKeep.uriId
        case r: NotificationInfoRequest.RequestKeep => r.id
      }
    }.toSet
    val userRequests = infoRequests.flatMap { infoRequest =>
      infoRequest.requests.collect {
        case r: NotificationInfoRequest.RequestUser => r.id
      }
    }.toSet
    val summaryRequests = infoRequests.flatMap { infoRequest =>
      infoRequest.requests.collect {
        case r: NotificationInfoRequest.RequestUriSummary => r.id
      }
    }

    val libsF = shoeboxServiceClient.getLibraryCardInfos(libRequests, ProcessedImageSize.Small.idealSize, userIdOpt)
    val orgsF = shoeboxServiceClient.getBasicOrganizationsByIds(orgRequests)
    val keepsF = shoeboxServiceClient.getCrossServiceKeepsByIds(keepRequests)
    val sourcesF = keepsF.flatMap { keepsById => shoeboxServiceClient.getSourceAttributionForKeeps(keepsById.keySet) }
    val summaryF = keepsF.flatMap { keeps =>
      val uriIdByKeep = summaryRequests.flatAugmentWith { kId =>
        keeps.get(kId).map(_.uriId)
      }.toMap
      roverServiceClient.getUriSummaryByUris(uriIdByKeep.values.toSet).map { summaries =>
        uriIdByKeep.flatMapValues(summaries.get)
      }
    }

    val userIdByExternalIdFut = for {
      orgs <- orgsF
      extIds = orgs.values.map(_.ownerId)
      userIds <- shoeboxServiceClient.getUserIdsByExternalIds(extIds.toSet)
    } yield userIds

    val batchedInfosF = for {
      libById <- libsF
      orgById <- orgsF
      keepsById <- keepsF
      userIdByExternalId <- userIdByExternalIdFut
      userIds = userRequests ++ userIdByExternalId.values.toSet ++ keepsById.values.flatMap(_.owner)
      userById <- shoeboxServiceClient.getBasicUsers(userIds.toSeq)
      userByExternalId = userIdByExternalId.mapValues(userById.get(_).get)
      summariesById <- summaryF
      sourceByKeepId <- sourcesF
    } yield {
      val keepAndSourceById = keepsById.map { case (keepId, keep) => keepId -> (keep, sourceByKeepId.get(keepId)) }
      new BatchedNotificationInfos(
        userById,
        userByExternalId,
        libById,
        keepAndSourceById,
        orgById,
        summariesById
      )
    }

    for {
      batchedInfos <- batchedInfosF
    } yield {
      notifs.flatMap {
        case NotificationWithItems(notif, items) =>
          val infoRequest = notifInfoRequests(notif)
          // TODO(ryan): can you write code that handles missing info elegantly instead of catching the inevitable KeyNotFoundException?
          Try(infoRequest.fn(batchedInfos)) match {
            case Failure(fail) =>
              // TODO(ryan): if we throw an exception while serializing a notification, we mark it as unread
              // this is to avoid situations where the "unread" count is non-zero but the user cannot see any unread notifs
              // A better strategy is to actually maintain notification integrity by ingesting the appropriate models
              // from Shoebox and deactivating notifications when they are no longer valid. Léo knows how to do this.
              notifCommander.setNotificationUnreadTo(notif.id.get, unread = false)
              log.error(fail.toString)
              None
            case Success(info) => Some(NotificationWithInfo(notif, items, info))
          }
      }
    }
  }

}
