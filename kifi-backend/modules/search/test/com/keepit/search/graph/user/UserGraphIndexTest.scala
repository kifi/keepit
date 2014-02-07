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
import com.keepit.shoebox.FakeShoeboxServiceClientImpl

class UserGraphIndexTest extends Specification with ApplicationInjector{

  def mkUserGraphIndexer(dir: IndexDirectory = new VolatileIndexDirectoryImpl): UserGraphIndexer = {
    new UserGraphIndexer(dir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
  }

  "UserGraphIndexer" should {

    "work" in {
      running(new TestApplication(FakeShoeboxServiceModule())) {
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val uids = (1 to 8).map{Id[User](_)}
        val (oddIds, evenIds) = uids.partition( id => id.id % 2 == 1)
        val initConn = uids.map{ id =>
          val conn = if (id.id % 2 == 0) evenIds.filter(_.id > id.id) else oddIds.filter(_.id > id.id)
          (id, conn.toSet)
        }.toMap

        client.saveConnections(initConn)
        val indexer = mkUserGraphIndexer()
        indexer.update()
        indexer.numDocs === 8
        indexer.sequenceNumber.value === 4*3

        var searcher = new UserGraphSearcher(indexer.getSearcher)
        searcher.getFriends(uids(0)).toSet === oddIds.filter(_.id != 1).map{_.id}.toSet
        searcher.getFriends(uids(1)).toSet === evenIds.filter(_.id != 2).map{_.id}.toSet

        val del1 = Map(uids(0) -> Set(uids(2)))
        val del2 = Map(uids(1) -> Set(uids(3), uids(5), uids(7)))
        client.deleteConnections(del1)
        client.deleteConnections(del2)

        indexer.update()
        indexer.numDocs == 7

        searcher = new UserGraphSearcher(indexer.getSearcher)
        searcher.getFriends(uids(0)).toSet === Set(5, 7)
        searcher.getFriends(uids(1)).toSet === Set[Long]()
        searcher.getFriends(uids(2)).toSet === Set(5, 7)
        searcher.getFriends(uids(3)).toSet === Set(6, 8)
        searcher.getFriends(uids(4)).toSet === Set(1, 3, 7)
        searcher.getFriends(uids(5)).toSet === Set(4, 8)
        searcher.getFriends(uids(6)).toSet === Set(1, 3, 5)
        searcher.getFriends(uids(7)).toSet === Set(4, 6)
      }
    }
  }
}
