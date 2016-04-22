package com.keepit.search.index.graph

import com.google.inject.Injector
import com.keepit.common.db._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.search.index.graph.user._
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.search.index.{ VolatileIndexDirectory }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.test.SearchTestInjector
import play.api.test.Helpers._

trait GraphTestHelper extends SearchTestInjector {

  val source = KeepSource.Fake
  val bigDataSize = 8000

  def initData()(implicit injector: Injector) = {
    val users = saveUsers(
      UserFactory.user().withId(1).withName("Agrajag", "").withUsername("test").get,
      UserFactory.user().withId(2).withName("Barmen", "").withUsername("test").get,
      UserFactory.user().withId(3).withName("Colin", "").withUsername("test").get,
      UserFactory.user().withId(4).withName("Dan", "").withUsername("test").get,
      UserFactory.user().withId(5).withName("Eccentrica", "").withUsername("test").get,
      UserFactory.user().withId(6).withName("Hactar", "").withUsername("test").get
    )
    val uris = saveURIs(
      NormalizedURI.withHash(title = Some("1"), normalizedUrl = "http://www.keepit.com/article1").withContentRequest(true),
      NormalizedURI.withHash(title = Some("2"), normalizedUrl = "http://www.keepit.com/article2").withContentRequest(true),
      NormalizedURI.withHash(title = Some("3"), normalizedUrl = "http://www.keepit.org/article3").withContentRequest(true),
      NormalizedURI.withHash(title = Some("4"), normalizedUrl = "http://www.findit.com/article4").withContentRequest(true),
      NormalizedURI.withHash(title = Some("5"), normalizedUrl = "http://www.findit.com/article5").withContentRequest(true),
      NormalizedURI.withHash(title = Some("6"), normalizedUrl = "http://www.findit.org/article6").withContentRequest(true)
    )
    (users, uris)
  }

  def superBigData()(implicit injector: Injector) = {
    val users = saveUsers(
      UserFactory.user().withId(1).withName("rich", "").withUsername("test").get,
      UserFactory.user().withId(2).withName("poor", "").withUsername("test").get
    )

    val uris = saveURIs(
      (1 to bigDataSize).map { i =>
        NormalizedURI.withHash(title = Some(s"${i}"), normalizedUrl = s"http://www.keepit.com/article${i}").withContentRequest(true)
      }: _*
    )
    (users, uris)
  }

  def shoeboxClient()(implicit injector: Injector) = inject[ShoeboxServiceClient]

  def saveUsers(users: User*)(implicit injector: Injector) = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveUsers(users: _*)
  }
  def saveURIs(uris: NormalizedURI*)(implicit injector: Injector) = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveURIs(uris: _*)
  }

  def saveBookmarksByURI(edgesByURI: Seq[(NormalizedURI, Seq[User])], mixPrivate: Boolean = false, uniqueTitle: Option[String] = None)(implicit injector: Injector): List[Keep] = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    val edges = for ((uri, users) <- edgesByURI; user <- users) yield (uri, user, uniqueTitle)
    val (privateEdges, publicEdges) = edges.partition { case (uri, user, _) => (uri.id.get.id + user.id.get.id) % 2 == 0 }
    val bookmarks = fakeShoeboxServiceClient.saveBookmarksByEdges(privateEdges, isPrivate = mixPrivate, source = source) ++
      fakeShoeboxServiceClient.saveBookmarksByEdges(publicEdges, isPrivate = false, source = source)
    bookmarks.toList
  }

  def saveBookmarksByEdges(edges: Seq[(NormalizedURI, User, Option[String])])(implicit injector: Injector): Seq[Keep] = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveBookmarksByEdges(edges, source = source)
  }

  def saveBookmarksByUser(edgesByUser: Seq[(User, Seq[NormalizedURI])], uniqueTitle: Option[String] = None)(implicit injector: Injector): Seq[Keep] = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveBookmarksByUser(edgesByUser, uniqueTitle = uniqueTitle, source = source)
  }

  def mkUserGraphsSearcherFactory()(implicit injector: Injector) = {
    val userGraphIndexer = new UserGraphIndexer(new VolatileIndexDirectory, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val searchFriendIndexer = new SearchFriendIndexer(new VolatileIndexDirectory, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
    val userGraphsSearcherFactory = new UserGraphsSearcherFactory(userGraphIndexer, searchFriendIndexer)
    (userGraphIndexer, searchFriendIndexer, userGraphsSearcherFactory)
  }

  def addConnections(connections: Map[Id[User], Set[Id[User]]])(implicit injector: Injector): Unit = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.saveConnections(connections)
  }
}
