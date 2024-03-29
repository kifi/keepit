package com.keepit.commanders

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.social.BasicUserRepo
import com.keepit.common.time._
import com.keepit.model._
import org.joda.time.DateTime
import play.api.libs.json.{ Json, JsValue }

object UserInboxCommander {
  abstract class PendingRequest(val kind: String, val sentAt: DateTime)
  case class PendingConnectionRequest(request: FriendRequest) extends PendingRequest("user", request.createdAt)
  case class PendingLibraryInvite(invite: LibraryInvite, library: Library) extends PendingRequest("library", invite.createdAt)
  case class PendingOrganizationInvite(invite: OrganizationInvite) extends PendingRequest("org", invite.createdAt)
}

@ImplementedBy(classOf[UserInboxCommanderImpl])
trait UserInboxCommander {
  def getPendingRequests(userId: Id[User], sentBefore: Option[DateTime], limit: Int): (Seq[JsValue], Int)
}

@Singleton
class UserInboxCommanderImpl @Inject() (
    db: Database,
    friendRequestRepo: FriendRequestRepo,
    libraryInviteRepo: LibraryInviteRepo,
    orgInviteRepo: OrganizationInviteRepo,
    basicUserRepo: BasicUserRepo,
    libraryCardCommander: LibraryCardCommander,
    organizationInfoCommander: OrganizationInfoCommander) extends UserInboxCommander with Logging {

  import UserInboxCommander._

  def getPendingRequests(userId: Id[User], sentBefore: Option[DateTime], limit: Int): (Seq[JsValue], Int) = {
    val allPendingRequests: Seq[PendingRequest] = db.readOnlyMaster { implicit session =>
      val pendingFriendRequests = friendRequestRepo.getByRecipient(userId).map(PendingConnectionRequest(_))
      val pendingLibraryInvites = libraryInviteRepo.getByUser(userId, excludeStates = LibraryInviteStates.notActive).groupBy(_._1.libraryId).mapValues(_.maxBy(_._1.createdAt)).values.map(PendingLibraryInvite.tupled(_))
      val pendingOrganizationInvites = orgInviteRepo.getByInviteeIdAndDecision(userId, InvitationDecision.PENDING).groupBy(_.organizationId).mapValues(_.maxBy(_.createdAt)).values.map(PendingOrganizationInvite(_))
      val allPendingRequests = pendingFriendRequests ++ pendingLibraryInvites ++ pendingOrganizationInvites
      sentBefore match {
        case None => allPendingRequests
        case Some(timestamp) => allPendingRequests.filter(_.sentAt isBefore timestamp)
      }
    }

    val pendingTotal = allPendingRequests.length
    val pendingRequests = allPendingRequests.sortBy(-_.sentAt.getMillis).take(limit)

    val basicUsersById = {
      val userIds = pendingRequests.collect {
        case PendingConnectionRequest(friendRequest) => friendRequest.senderId
        case PendingLibraryInvite(_, library) => library.ownerId
      }
      db.readOnlyMaster { implicit session => basicUserRepo.loadAll(userIds.toSet) }
    }

    val pending = pendingRequests.map { request =>
      val fromJson = request match {
        case PendingConnectionRequest(friendRequest) => Json.toJson(basicUsersById(friendRequest.senderId))
        case PendingLibraryInvite(_, library) => Json.toJson(libraryCardCommander.createLibraryCardInfo(library, basicUsersById(library.ownerId), Some(userId), false, ProcessedImageSize.Medium.idealSize))
        case PendingOrganizationInvite(invite) => Json.toJson(organizationInfoCommander.getBasicOrganizationView(invite.organizationId, Some(userId), None))
      }
      Json.obj("kind" -> request.kind, "sentAt" -> request.sentAt, "from" -> fromJson)
    }

    (pending, pendingTotal)
  }
}
