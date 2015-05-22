package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.model.{ FriendRequest, FriendRequestRepo, UserConnectionRepo, UserConnectionStates }
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification

import concurrent.Await
import concurrent.duration.Duration

class FriendStatusCommanderTest extends Specification with ShoeboxTestInjector {

  "FriendStatusCommander" should {
    "augment one basic user with friend status" in {
      withDb() { implicit injector =>
        val Seq(user1, user2, user3, user4) = db.readWrite { implicit s =>
          testFactory.createUsersWithConnections()
        }
        val commander = inject[FriendStatusCommander]

        db.readOnlyMaster { implicit s =>
          val u2 = commander.augmentUser(user1.id.get, user2.id.get, BasicUser.fromUser(user2))
          u2.isFriend === Some(true)
          u2.friendRequestSentAt === None
          u2.friendRequestReceivedAt === None

          val u4 = commander.augmentUser(user1.id.get, user4.id.get, BasicUser.fromUser(user4))
          u4.isFriend === Some(false)
          u4.friendRequestSentAt === None
          u4.friendRequestReceivedAt === None
        }

        val req1to4 = db.readWrite { implicit s =>
          inject[FriendRequestRepo].save(FriendRequest(senderId = user1.id.get, recipientId = user4.id.get, messageHandle = None))
        }
        db.readOnlyMaster { implicit s =>
          val u4 = commander.augmentUser(user1.id.get, user4.id.get, BasicUser.fromUser(user4))
          u4.isFriend === Some(false)
          u4.friendRequestSentAt === Some(req1to4.createdAt)
          u4.friendRequestReceivedAt === None
        }
      }
    }

    "augment several basic users with friend status" in {
      withDb() { implicit injector =>
        val Seq(user1, user2, user3, user4) = db.readWrite { implicit s =>
          testFactory.createUsersWithConnections()
        }
        val commander = inject[FriendStatusCommander]

        val req4to1 = db.readWrite { implicit s =>
          val (ucRepo, frRepo) = (inject[UserConnectionRepo], inject[FriendRequestRepo])
          ucRepo.save(ucRepo.getConnectionOpt(user1.id.get, user3.id.get).get.copy(state = UserConnectionStates.UNFRIENDED))
          frRepo.save(FriendRequest(senderId = user4.id.get, recipientId = user1.id.get, messageHandle = None))
        }
        db.readOnlyMaster { implicit s =>
          val map = commander.augmentUsers(user1.id.get, Map(
            user2.id.get -> BasicUser.fromUser(user2),
            user3.id.get -> BasicUser.fromUser(user3),
            user4.id.get -> BasicUser.fromUser(user4)))
          val u2 = map(user2.id.get)
          u2.isFriend === Some(true)
          u2.friendRequestSentAt === None
          u2.friendRequestReceivedAt === None
          val u3 = map(user3.id.get)
          u3.isFriend === Some(false)
          u3.friendRequestSentAt === None
          u3.friendRequestReceivedAt === None
          val u4 = map(user4.id.get)
          u4.isFriend === Some(false)
          u4.friendRequestSentAt === None
          u4.friendRequestReceivedAt === Some(req4to1.createdAt)
        }
      }
    }
  }
}
