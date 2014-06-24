package com.keepit.model

import org.specs2.mutable.Specification
import com.keepit.test.ShoeboxTestInjector
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber

class SearchFriendTest extends Specification with ShoeboxTestInjector {
  "SearchFriendRepo" should {
    "filter and unfilter friends" in {
      withDb() { implicit injector =>
        val users = (1 to 3).map(Id[User](_))
        db.readWrite { implicit s =>
          userConnRepo.addConnections(users.head, users.tail.toSet)

          searchFriendRepo.excludeFriends(users(0), Set(users(1), users(2)))

          searchFriendRepo.getSearchFriends(users(0)) must beEmpty

          searchFriendRepo.includeFriends(users(0), Set(users(1), users(2))) === 2
          searchFriendRepo.excludeFriend(users(0), users(1)) === true
          searchFriendRepo.excludeFriend(users(0), users(1)) === false

          searchFriendRepo.getSearchFriends(users(0)) === Set(users(2))

          searchFriendRepo.includeFriends(users(0), Set(users(1), users(2))) === 1

          searchFriendRepo.getSearchFriends(users(0)) === Set(users(1), users(2))
          searchFriendRepo.getSearchFriends(users(1)) === Set(users(0))
        }
      }
    }

    "seqNum works" in {
      withDb(){ implicit injector =>
        val users = (1 to 4).map(Id[User](_))
        db.readWrite { implicit s =>
          userConnRepo.addConnections(users.head, users.tail.toSet)
          userConnRepo.assignSequenceNumbers(1000)
          searchFriendRepo.excludeFriends(users(0), Set(users(1)))

          userConnRepo.getUserConnectionChanged(SequenceNumber.ZERO, fetchSize = 10).map{_.seq.value}.toSet === Set(1, 2, 3)
          searchFriendRepo.getSearchFriendsChanged(SequenceNumber.ZERO, fetchSize = 10).map{_.seq.value}.toSet === Set(1)

          searchFriendRepo.excludeFriend(users(0), users(2))
          searchFriendRepo.getSearchFriendsChanged(SequenceNumber(1), fetchSize = 10).map{_.seq.value}.toSet === Set(2)
          searchFriendRepo.includeFriend(users(0), users(1))
          searchFriendRepo.getSearchFriendsChanged(SequenceNumber(2), fetchSize = 10).map{_.seq.value}.toSet === Set(3)

          userConnRepo.unfriendConnections(users(0), Set(users(1)))
          userConnRepo.assignSequenceNumbers(1000)
          userConnRepo.getUserConnectionChanged(SequenceNumber(3), fetchSize = 10).map{_.seq.value}.toSet === Set(4)
          
          userConnRepo.deactivateAllConnections(users(0))
          userConnRepo.assignSequenceNumbers(1000)
          userConnRepo.getUserConnectionChanged(SequenceNumber(4), fetchSize = 10).map{_.seq.value}.toSet === Set(5, 6, 7)
        }
      }
    }

    "use cache properly" in {
      withDb() { implicit injector =>
        val users = (1 to 3).map(Id[User](_))
        db.readWrite { implicit s =>
          userConnRepo.addConnections(users.head, users.tail.toSet)
          searchFriendRepo.excludeFriend(users(0), users(1))
        }
        sessionProvider.doWithoutCreatingSessions {
          db.readOnly { implicit s =>
            searchFriendRepo.getSearchFriends(users(0)) === Set(users(2))
          }
        }
        db.readWrite { implicit s =>
          userConnRepo.unfriendConnections(users(0), Set(users(2)))
          searchFriendRepo.getSearchFriends(users(0)) must beEmpty
          userConnRepo.addConnections(users(0), Set(users(2)), requested = true)
          searchFriendRepo.getSearchFriends(users(0)) === Set(users(2))
        }
        sessionProvider.doWithoutCreatingSessions {
          db.readOnly { implicit s => searchFriendRepo.getSearchFriends(users(0)) } === Set(users(2))
        }
      }
    }
  }
}
