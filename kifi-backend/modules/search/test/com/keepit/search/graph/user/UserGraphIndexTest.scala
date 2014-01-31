package com.keepit.search.graph.user

import org.specs2.mutable._
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.common.db.SequenceNumber
import org.apache.lucene.index.IndexWriterConfig
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.search.index.{VolatileIndexDirectoryImpl, IndexDirectory, DefaultAnalyzer}
import play.api.test.Helpers._
import org.apache.lucene.util.Version
import com.keepit.inject._
import com.keepit.test._
import com.keepit.shoebox.FakeShoeboxServiceModule

class UserGraphIndexTest extends Specification with ApplicationInjector{
  import UserGraphIndexer.UserGraphIndexable

  def uid(x: Int) = Id[User](x)

  def mkUserGraphIndexer(dir: IndexDirectory = new VolatileIndexDirectoryImpl): UserGraphIndexer = {
    new UserGraphIndexer(dir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
  }

  "UserGraphIndexer" should {
    "work" in {
      running(new TestApplication(FakeShoeboxServiceModule())) {
        val indexables = Seq(
          new UserGraphIndexable(uid(1), SequenceNumber(1), isDeleted = false, Seq(uid(2), uid(3))),
          new UserGraphIndexable(uid(2), SequenceNumber(2), isDeleted = false, Seq(uid(3), uid(4))),
          new UserGraphIndexable(uid(3), SequenceNumber(1), isDeleted = false, Seq(uid(4), uid(1))),
          new UserGraphIndexable(uid(4), SequenceNumber(1), isDeleted = false, Seq(uid(1), uid(2)))
        )

        val indexer = mkUserGraphIndexer()
        indexer.update(indexables) === 4

        val searcher = new UserGraphSearcher(indexer.getSearcher)
        searcher.getFriends(uid(1)).toSet === Set(2L, 3L)
      }
    }
  }
}
