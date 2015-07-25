package com.keepit.search.user

import com.keepit.model.{ UserFactory }
import com.keepit.search.util.IdFilterCompressor
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, FakeShoeboxServiceModule, ShoeboxServiceClient }
import com.keepit.test.CommonTestInjector
import org.specs2.mutable.Specification

class DeprecatedUserSearchFilterTest extends Specification with CommonTestInjector {

  private def setup(implicit client: FakeShoeboxServiceClientImpl) = {

    val users = client.saveUsers(
      UserFactory.user().withId(1).withName("abc", "xyz").withUsername("test").get,
      UserFactory.user().withId(2).withName("alpha", "one").withUsername("test").get,
      UserFactory.user().withId(3).withName("alpha", "two").withUsername("test").get,
      UserFactory.user().withId(4).withName("alpha", "three").withUsername("test").get
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
        val factory = inject[DeprecatedUserSearchFilterFactory]
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
        val factory = inject[DeprecatedUserSearchFilterFactory]
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
        val factory = inject[DeprecatedUserSearchFilterFactory]
        val users = setup(client)
        val filter = factory.nonFriendsOnly(users(0).id.get, context = None)
        filter.accept(users(0).id.get.id) === false
        filter.accept(users(1).id.get.id) === false
        filter.accept(users(2).id.get.id) === true
        filter.accept(users(3).id.get.id) === false
      }
    }
  }
}
