package com.keepit.model

import com.keepit.eliza.model.MessageHandle
import com.keepit.notify.model.Recipient

import org.specs2.mutable.Specification

import com.keepit.common.db.Id
import com.keepit.test.ShoeboxTestInjector
import com.keepit.eliza.{ ElizaServiceClient, FakeElizaServiceClientImpl }

class FriendRequestTest extends Specification with ShoeboxTestInjector {

  "FriendRequestRepo" should {
    "get active friend requests by sender and recipient" in {
      val users = (1 to 3).map(Id[User](_)).toSeq
      withDb() { implicit injector =>
        val (fr1, fr2) = db.readWrite { implicit s =>
          (
            friendRequestRepo.save(FriendRequest(senderId = users(0), recipientId = users(1), messageHandle = None)),
            friendRequestRepo.save(FriendRequest(senderId = users(0), recipientId = users(2), messageHandle = None))
          )
        }
        db.readOnlyMaster { implicit s =>
          friendRequestRepo.getBySender(users(0)).map(_.recipientId) must contain(exactly(users(1), users(2)))
          friendRequestRepo.getByRecipient(users(1)).map(_.senderId) must contain(exactly(users(0)))
          friendRequestRepo.getByRecipient(users(2)).map(_.senderId) must contain(exactly(users(0)))
          friendRequestRepo.getBySenderAndRecipient(users(0), users(1)) must beSome(fr1)
        }
        db.readWrite { implicit s =>
          friendRequestRepo.save(fr1.copy(state = FriendRequestStates.ACCEPTED))
        }
        db.readOnlyMaster { implicit s =>
          friendRequestRepo.getBySender(users(0)).map(_.recipientId) must contain(exactly(users(2)))
          friendRequestRepo.getByRecipient(users(1)) must beEmpty
          friendRequestRepo.getCountByRecipient(users(2)) must_== 1
          friendRequestRepo.getByRecipient(users(2)).map(_.senderId) must contain(exactly(users(0)))
          friendRequestRepo.getBySenderAndRecipient(users(0), users(1)) must beNone
        }
        db.readWrite { implicit s =>
          friendRequestRepo.save(fr2.copy(state = FriendRequestStates.IGNORED))
        }
        db.readOnlyMaster { implicit s =>
          friendRequestRepo.getBySender(users(0)) must beEmpty
          friendRequestRepo.getBySender(users(0), states = Set(FriendRequestStates.ACCEPTED,
            FriendRequestStates.IGNORED)).map(_.recipientId) must contain(exactly(users(1), users(2)))
          friendRequestRepo.getByRecipient(users(1)) must beEmpty
          friendRequestRepo.getCountByRecipient(users(2)) must_== 0
          friendRequestRepo.getByRecipient(users(2)) must beEmpty
          friendRequestRepo.getBySenderAndRecipient(users(0), users(1)) must beNone
          friendRequestRepo.getBySenderAndRecipient(users(0), users(2)) must beNone
          friendRequestRepo.getBySenderAndRecipient(users(0), users(1), Set(FriendRequestStates.ACCEPTED)) must beSome
        }
      }
    }
    "with messageHandle" in {
      val users = (1 to 3).map(Id[User](_)).toSeq
      withDb() { implicit injector =>
        val eliza = inject[ElizaServiceClient].asInstanceOf[FakeElizaServiceClientImpl]
        eliza.completedNotifications.size === 0
        val (fr1, fr2) = db.readWrite { implicit s =>
          (
            friendRequestRepo.save(FriendRequest(senderId = users(0), recipientId = users(1), messageHandle = Some(Id[MessageHandle](1)))),
            friendRequestRepo.save(FriendRequest(senderId = users(0), recipientId = users(2), messageHandle = Some(Id[MessageHandle](22))))
          )
        }
        eliza.completedNotifications.size === 0
        db.readOnlyMaster { implicit s =>
          friendRequestRepo.get(fr1.id.get).messageHandle.get.id === 1
          friendRequestRepo.get(fr2.id.get).messageHandle.get.id === 22
        }
        db.readWrite { implicit s =>
          friendRequestRepo.save(fr1.copy(state = FriendRequestStates.ACCEPTED))
          friendRequestRepo.save(fr2.copy(state = FriendRequestStates.IGNORED))
        }
        eliza.completedNotifications.size === 2
        eliza.completedNotifications(0) === Recipient(users(0))
        eliza.completedNotifications(1) === Recipient(users(0))
      }
    }
  }
}
