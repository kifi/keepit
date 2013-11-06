package com.keepit.search.user

import org.specs2.mutable.Specification
import com.google.inject.Singleton
import com.keepit.inject.ApplicationInjector
import com.keepit.model.User
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.TestApplication
import play.api.test.Helpers.running
import com.keepit.search.IdFilterCompressor



class UserSearchFilterTest extends Specification with ApplicationInjector {

  private def setup(implicit client: FakeShoeboxServiceClientImpl) = {

    val users = client.saveUsers(
      User(firstName = "abc", lastName = "xyz"),
      User(firstName = "alpha", lastName = "one"),
      User(firstName = "alpha", lastName = "two"),
      User(firstName = "alpha", lastName = "three")
    )
    val ids = users.map{_.id.get}
    val conn = Map(ids(0) -> Set(ids(1), ids(3)))
    client.saveConnections(conn)
    users
  }

  def factory = inject[UserSearchFilterFactory]

  "default search filter" should {
    "work" in {
      running(new TestApplication(FakeShoeboxServiceModule())) {
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        setup(client)
        var filter = factory.default(None)
        filter.accept(1) === true

        val context = IdFilterCompressor.fromSetToBase64(Set(1))
        filter = factory.default(Some(context))
        filter.accept(1) === false
      }
    }
  }

  "friend-only filter" should {
    "work" in {
      running(new TestApplication(FakeShoeboxServiceModule())) {
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val users = setup(client)
        var filter = factory.friendsOnly(users(0).id.get, None)
        filter.accept(users(1).id.get.id) === true
        filter.accept(users(2).id.get.id) === false
        filter.accept(users(3).id.get.id) === true

        val context = IdFilterCompressor.fromSetToBase64(Set(users(3).id.get.id))
        filter = factory.friendsOnly(users(0).id.get, Some(context))
        filter.accept(users(1).id.get.id) === true
        filter.accept(users(2).id.get.id) === false
        filter.accept(users(3).id.get.id) === false

      }
    }
  }

  "Nonfriends-only filter" should {
    "work" in {
      running(new TestApplication(FakeShoeboxServiceModule())) {
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val users = setup(client)
        var filter = factory.nonFriendsOnly(users(0).id.get, context = None)
        filter.accept(users(0).id.get.id) === false
        filter.accept(users(1).id.get.id) === false
        filter.accept(users(2).id.get.id) === true
        filter.accept(users(3).id.get.id) === false
      }
    }
  }
}
