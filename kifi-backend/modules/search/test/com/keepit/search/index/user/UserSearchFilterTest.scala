package com.keepit.search.index.user

import com.keepit.search.engine.user.UserSearchFilterFactory
import org.specs2.mutable.Specification
import com.keepit.model.{ Username, User }
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.CommonTestInjector
import com.keepit.search.util.IdFilterCompressor

class UserSearchFilterTest extends Specification with CommonTestInjector {

  private def setup(implicit client: FakeShoeboxServiceClientImpl) = {

    val users = client.saveUsers(
      User(firstName = "abc", lastName = "xyz", username = Username("test"), normalizedUsername = "test"),
      User(firstName = "alpha", lastName = "one", username = Username("test"), normalizedUsername = "test"),
      User(firstName = "alpha", lastName = "two", username = Username("test"), normalizedUsername = "test"),
      User(firstName = "alpha", lastName = "three", username = Username("test"), normalizedUsername = "test")
    )
    val ids = users.map { _.id.get }
    val conn = Map(ids(0) -> Set(ids(1), ids(3)))
    client.saveConnections(conn)
    users
  }

  "default search filter" should {
    "work" in {
      withInjector(FakeShoeboxServiceModule()) { implicit injector =>
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val factory = inject[UserSearchFilterFactory]
        val users = setup(client)
        var filter = factory.default(None)
        filter.accept(1) === true

        val context = IdFilterCompressor.fromSetToBase64(Set(1))
        filter = factory.default(None, Some(context))
        filter.accept(1) === false

        filter = factory.default(users(0).id, context = None, excludeSelf = true)
        filter.accept(1) === false
        filter.accept(2) === true
      }
    }
  }

  "friend-only filter" should {
    "work" in {
      withInjector(FakeShoeboxServiceModule()) { implicit injector =>
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val factory = inject[UserSearchFilterFactory]
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
      withInjector(FakeShoeboxServiceModule()) { implicit injector =>
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val factory = inject[UserSearchFilterFactory]
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
