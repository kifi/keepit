package com.keepit.search.graph.user

import org.specs2.mutable._
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.index.{ VolatileIndexDirectory, IndexDirectory, DefaultAnalyzer }
import play.api.test.Helpers._
import com.keepit.inject._
import com.keepit.test._
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.shoebox.FakeShoeboxServiceClientImpl

class SearchFriendIndexerTest extends Specification with ApplicationInjector {

  def mkSearchFriendIndexer(dir: IndexDirectory = new VolatileIndexDirectory) = {
    new SearchFriendIndexer(dir, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
  }

  "searchFriend indexer" should {
    "work" in {
      running(new CommonTestApplication(FakeShoeboxServiceModule())) {
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val uids = (1 to 4).map { Id[User](_) }
        client.excludeFriend(uids(0), uids(1))
        client.excludeFriend(uids(0), uids(2))
        client.excludeFriend(uids(1), uids(3))

        val indexer = mkSearchFriendIndexer()
        indexer.update()
        indexer.numDocs === 2

        var searcher = new SearchFriendSearcher(indexer.getSearcher)
        searcher.getUnfriended(uids(0)) === Set(uids(1).id, uids(2).id)
        searcher.getUnfriended(uids(1)) === Set(uids(3).id)
      }
    }
  }

}