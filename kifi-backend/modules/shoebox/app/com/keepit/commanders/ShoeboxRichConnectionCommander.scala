package com.keepit.commanders

import com.keepit.abook.ABookServiceClient
import com.keepit.common.queue.{
  RichConnectionUpdateMessage,
  CreateRichConnection,
  RecordKifiConnection,
  RecordInvitation,
  RecordFriendUserId
}
import com.keepit.common.db.slick.{
  RepoModification,
  RepoEntryAdded,
  RepoEntryUpdated
}
import com.keepit.model.{
  SocialConnection,
  UserConnection,
  Invitation,
  SocialUserInfo,
  SocialUserInfoRepo
}
import com.keepit.common.db.slick.Database

import com.kifi.franz.FormattedSQSQueue

import com.google.inject.{Inject, Singleton, Provider}

@Singleton
class ShoeboxRichConnectionCommander @Inject() (
    abook: ABookServiceClient,
    queue: FormattedSQSQueue[RichConnectionUpdateMessage],
    socialUserInfoRepo: Provider[SocialUserInfoRepo],
    db: Database
  ) extends RemoteRichConnectionCommander(abook, queue) {

  def processSocialConnectionChange(change: RepoModification[SocialConnection]): Unit = {
    change match {
      case RepoEntryAdded(socialConnection) => {
        val (sUser1, sUser2) = db.readOnly { implicit session =>
          (socialUserInfoRepo.get.get(socialConnection.socialUser1), socialUserInfoRepo.get.get(socialConnection.socialUser2))
        }
        sUser1.userId.map { userId =>
          processUpdate(CreateRichConnection(userId, sUser1.id.get, sUser2))
        }
        sUser2.userId.map { userId =>
          processUpdate(CreateRichConnection(userId, sUser2.id.get, sUser1))
        }
      }
      case _ =>
    }
  }

  def processUserConnectionChange(change: RepoModification[UserConnection]): Unit = {
    change match {
      case RepoEntryAdded(userConnection) => {
        processUpdate(RecordKifiConnection(userConnection.user1, userConnection.user2))
      }
      case _ =>
    }
  }

  def processInvitationChange(change: RepoModification[Invitation]): Unit = { //ZZZ incomplete. Need to deal with networkType (unused on the other end right now) and email invites
    change match {
      case RepoEntryAdded(invitation) => {
        invitation.senderUserId.map { userId =>
          processUpdateImmediate(RecordInvitation(userId, invitation.id.get, "thisisthenetworktype", invitation.recipientSocialUserId, None))
        }
      }
      case _ =>
    }
  }

  def processSocialUserChange(change: RepoModification[SocialUserInfo]): Unit = { //ZZZ incomplete. Need to deal with networkType (unused on the other end right now) and email
    change match {
      case RepoEntryAdded(socialUserInfo) => {
        socialUserInfo.userId.map{ userId =>
          processUpdate(RecordFriendUserId("thisisthenetworktype", socialUserInfo.id, None, userId))
        }
      }
      case RepoEntryUpdated(socialUserInfo) => {
        socialUserInfo.userId.map{ userId =>
          processUpdate(RecordFriendUserId("thisisthenetworktype", socialUserInfo.id, None, userId))
        }
      }
      case _ =>
    }
  }

}
