package com.keepit.notify.info

import com.google.inject.Inject
import com.keepit.commanders.ProcessedImageSize
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.logging.Logging
import com.keepit.eliza.model.{ NotificationWithInfo, NotificationWithItems }
import com.keepit.model.NormalizedURI
import com.keepit.notify.model.{ Recipient, UserRecipient }
import com.keepit.rover.RoverServiceClient
import com.keepit.shoebox.ShoeboxServiceClient

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Try }

class NotificationInfoGenerator @Inject() (
    shoeboxServiceClient: ShoeboxServiceClient,
    roverServiceClient: RoverServiceClient,
    notificationKindInfoRequests: NotificationKindInfoRequests,
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
    val keepsF = shoeboxServiceClient.getBasicKeepsByIds(keepRequests)
    val summaryF = keepsF.flatMap { keeps =>
      val uriToKeepId = (for {
        keepId <- summaryRequests
        keep <- keeps.get(keepId)
        uriId <- keep.uriId.flatMap(u => NormalizedURI.decodePublicId(u).toOption)
      } yield uriId -> keepId).toMap
      roverServiceClient.getUriSummaryByUris(uriToKeepId.keys.toSet).map { s =>
        s.map(sv => uriToKeepId(sv._1) -> sv._2)
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
      keepByid <- keepsF
      userIdByExternalId <- userIdByExternalIdFut
      userIds = userRequests ++ userIdByExternalId.values.toSet
      userById <- shoeboxServiceClient.getBasicUsers(userIds.toSeq)
      userByExternalId = userIdByExternalId.mapValues(userById.get(_).get)
      summariesById <- summaryF
    } yield {
      new BatchedNotificationInfos(
        userById,
        userByExternalId,
        libById,
        keepByid,
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
