package com.keepit.commanders

import com.google.inject.{ Singleton, Inject }
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

trait UserInboxCommander {
  def getPendingRequests(userId: Id[User], sentBefore: Option[DateTime], limit: Int): Seq[JsValue]
}

@Singleton
class UserInboxCommanderImpl @Inject() (
    db: Database,
    friendRequestRepo: FriendRequestRepo,
    libraryInviteRepo: LibraryInviteRepo,
    orgInviteRepo: OrganizationInviteRepo,
    basicUserRepo: BasicUserRepo,
    libraryInfoCommander: LibraryInfoCommander,
    organizationCommander: OrganizationCommander) extends UserInboxCommander with Logging {

  import UserInboxCommander._

  def getPendingRequests(userId: Id[User], sentBefore: Option[DateTime], limit: Int): Seq[JsValue] = {
    val pendingRequests: Seq[PendingRequest] = db.readOnlyMaster { implicit session =>
      val pendingFriendRequests = friendRequestRepo.getByRecipient(userId).map(PendingConnectionRequest(_))
      val pendingLibraryInvites = libraryInviteRepo.getByUser(userId, excludeStates = LibraryInviteStates.notActive).map(PendingLibraryInvite.tupled(_))
      val pendingOrganizationInvites = orgInviteRepo.getByInviteeIdAndDecision(userId, InvitationDecision.PENDING).map(PendingOrganizationInvite(_))
      val allPendingRequests = pendingFriendRequests ++ pendingLibraryInvites ++ pendingOrganizationInvites
      sentBefore match {
        case None => allPendingRequests
        case Some(timestamp) => allPendingRequests.filter(_.sentAt isBefore timestamp)
      }
    }.sortBy(-_.sentAt.getMillis).take(limit)

    val basicUsersById = {
      val userIds = pendingRequests.collect {
        case PendingConnectionRequest(friendRequest) => friendRequest.senderId
        case PendingLibraryInvite(_, library) => library.ownerId
      }
      db.readOnlyMaster { implicit session => basicUserRepo.loadAll(userIds.toSet) }
    }

    pendingRequests.map { request =>
      val fromJson = request match {
        case PendingConnectionRequest(friendRequest) => Json.toJson(basicUsersById(friendRequest.senderId))
        case PendingLibraryInvite(_, library) => Json.toJson(libraryInfoCommander.createLibraryCardInfo(library, basicUsersById(library.ownerId), Some(userId), false, ProcessedImageSize.Medium.idealSize))
        case PendingOrganizationInvite(invite) => Json.toJson(organizationCommander.getBasicOrganizationView(invite.organizationId, Some(userId), None))
      }
      Json.obj("kind" -> request.kind, "sentAt" -> request.sentAt, "from" -> fromJson)
    }
  }
}
