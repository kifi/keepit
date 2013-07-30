package com.keepit.model

import org.specs2.mutable.Specification
import com.keepit.test.ShoeboxTestInjector
import com.keepit.common.db.Id

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
