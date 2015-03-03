package com.keepit.search.index.sharding

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.search.index.article.ArticleRecord
import com.keepit.search.index.article.ArticleRecordSerializer
import com.keepit.search.index.graph.keep.{ KeepRecord, KeepFields, ShardedKeepIndexer }
import com.keepit.search.test.SearchTestInjector
import org.apache.lucene.index.Term
import org.specs2.mutable._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.search.index.graph.collection._
import com.keepit.search.SearchTestHelper
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import scala.concurrent._
import scala.concurrent.duration._

class IndexShardingTest extends Specification with SearchTestInjector with SearchTestHelper {

  implicit private val activeShards = ActiveShards((new ShardSpecParser).parse("0,1/2"))
  implicit private val publicIdConfig = PublicIdConfiguration("secret key")
  val emptyFuture = Future.successful(Set[Long]())

  "ShardedIndexer" should {
    "create index shards" in {
      withInjector(helperModules: _*) { implicit injector =>
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.take(9).toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        val bookmarks = saveBookmarksByURI(expectedUriToUserEdges)

        val collKeeps = Seq(
          bookmarks.filter { b => b.userId == users(0).id.get && b.uriId.id % 3 == 0 },
          bookmarks.filter { b => b.userId == users(1).id.get && b.uriId.id % 3 == 1 },
          bookmarks.filter { b => b.userId == users(1).id.get && b.uriId.id % 3 == 2 }
        )
        val collections = collKeeps.zipWithIndex.map {
          case (s, i) =>
            val Seq(collection) = saveCollections(Collection(userId = users(i).id.get, name = Hashtag(s"coll $i")))
            saveBookmarksToCollection(collection.id.get, s: _*)
            collection
        }

        val store = mkStore(uris)
        val (_, indexer, _, _, _, keepIndexer, _, _) = initIndexes(store)

        indexer.isInstanceOf[ShardedArticleIndexer] === true
        keepIndexer.isInstanceOf[ShardedKeepIndexer] === true

        indexer.update() === uris.size
        Await.result(keepIndexer.asyncUpdate(), Duration(60, SECONDS))

        users.foreach { user =>
          val userId = user.id.get
          activeShards.local.foreach { shard =>
            val articleSearcher = indexer.getIndexer(shard).getSearcher

            uris.foreach { uri =>
              articleSearcher.getDecodedDocValue[ArticleRecord]("rec", uri.id.get.id)(ArticleRecordSerializer.fromByteArray).isDefined === shard.contains(uri.id.get)
            }

            val keepSearcher = keepIndexer.getIndexer(shard).getSearcher
            val keeps = keepSearcher.findSecondaryIds(new Term(KeepFields.userField, userId.toString), KeepFields.uriIdField).toArray.toSet
            keeps.forall(id => shard.contains(Id[NormalizedURI](id))) === true

            bookmarks.filter(_.userId == userId).foreach { bookmark =>
              if (shard.contains(bookmark.uriId)) {
                keeps.contains(bookmark.uriId.id) === true
                keepSearcher.getDecodedDocValue[KeepRecord](KeepFields.recordField, bookmark.id.get.id)(KeepRecord.fromByteArray).isDefined === true
              } else {
                keeps.contains(bookmark.uriId.id) === false
              }
            }
          }

          val keeps = bookmarks.filter(_.userId == userId).map(_.uriId.id).toSet
          val shardedKeeps = activeShards.local.map { shard =>
            val keepSearcher = keepIndexer.getIndexer(shard).getSearcher
            keepSearcher.findSecondaryIds(new Term(KeepFields.userField, userId.toString), KeepFields.uriIdField).toArray.toSet
          }
          shardedKeeps.foldLeft(0) { (sum, set) => sum + set.size } === keeps.size
          shardedKeeps.flatten === keeps
        }
      }
      1 === 1
    }

    "handle URI migration" in {
      withInjector(helperModules: _*) { implicit injector =>
        val (Seq(user), uris) = initData(numUsers = 1, numUris = 20)
        val userId = user.id.get
        val expectedUriToUserEdges = uris.take(5).map(_ -> Seq(user))
        val bookmarks = saveBookmarksByURI(expectedUriToUserEdges)
        val collection = {
          val Seq(collection) = saveCollections(Collection(userId = userId, name = Hashtag("mutating collection")))
          saveBookmarksToCollection(collection.id.get, bookmarks: _*)
          collection
        }

        val store = mkStore(uris)
        val (collectionGraph, indexer, _, _, _, keepIndexer, _, _) = initIndexes(store)

        indexer.isInstanceOf[ShardedArticleIndexer] === true
        collectionGraph.isInstanceOf[ShardedCollectionIndexer] === true

        Await.result(keepIndexer.asyncUpdate(), Duration(60, SECONDS))
        collectionGraph.update()

        val originalBookmark = bookmarks.find(_.userId == userId).get
        val sourceShard = activeShards.local.find(_.contains(originalBookmark.uriId)).get
        val targetShard = activeShards.local.find(!_.contains(originalBookmark.uriId)).get
        val targetUri = uris.filter(u => targetShard.contains(u.id.get)).find(u => !bookmarks.exists(k => k.uriId == u.id.get)).get

        Await.result(keepIndexer.asyncUpdate(), Duration(60, SECONDS))
        collectionGraph.update()

        def getKeepSize(shard: Shard[NormalizedURI]) = {
          val keepSearcher = keepIndexer.getIndexer(shard).getSearcher
          keepSearcher.findSecondaryIds(new Term(KeepFields.userField, userId.toString), KeepFields.uriIdField).toArray.toSet.size
        }
        def getCollectionSize(shard: Shard[NormalizedURI]) = CollectionSearcher(collectionGraph.getIndexer(shard)).getCollectionToUriEdgeSet(collection.id.get).size

        val oldKeepSizes = activeShards.local.map { shard => (shard -> getKeepSize(shard)) }.toMap
        val oldCollSizes = activeShards.local.map { shard => (shard -> getCollectionSize(shard)) }.toMap

        // migrate URI
        val Seq(migratedBookmark) = saveBookmarks(originalBookmark.copy(uriId = targetUri.id.get))
        saveCollections(collection)

        Await.result(keepIndexer.asyncUpdate(), Duration(60, SECONDS))
        collectionGraph.update()

        val newKeepSizes = activeShards.local.map { shard => (shard -> getKeepSize(shard)) }.toMap
        val newCollSizes = activeShards.local.map { shard => (shard -> getCollectionSize(shard)) }.toMap

        activeShards.local.foreach { shard =>
          val keepSearcher = keepIndexer.getIndexer(shard).getSearcher
          val collectionSearcher = CollectionSearcher(collectionGraph.getIndexer(shard))

          if (shard == sourceShard) {
            keepSearcher.findSecondaryIds(new Term(KeepFields.userField, userId.toString), KeepFields.uriIdField).toArray.contains(originalBookmark.uriId.id) === false
            collectionSearcher.getCollectionToUriEdgeSet(collection.id.get).destIdSet.contains(originalBookmark.uriId) === false

            newKeepSizes(shard) === oldKeepSizes(shard) - 1
            newCollSizes(shard) === oldCollSizes(shard) - 1
          } else if (shard == targetShard) {
            keepSearcher.findSecondaryIds(new Term(KeepFields.userField, userId.toString), KeepFields.uriIdField).toArray.contains(migratedBookmark.uriId.id) === true
            collectionSearcher.getCollectionToUriEdgeSet(collection.id.get).destIdSet.contains(migratedBookmark.uriId) === true

            newKeepSizes(shard) === oldKeepSizes(shard) + 1
            newCollSizes(shard) === oldCollSizes(shard) + 1
          } else {
            newKeepSizes(shard) === oldKeepSizes(shard)
            newCollSizes(shard) === oldCollSizes(shard)
          }
        }
        1 === 1
      }
    }

    "correctly reindex" in {
      withInjector(helperModules: _*) { implicit injector =>
        val numUris = 5
        val (uris, shoebox) = {
          val uris = (0 until numUris).map { n =>
            NormalizedURI.withHash(title = Some("a" + n),
              normalizedUrl = "http://www.keepit.com/article" + n, state = SCRAPED)
          }.toList
          val fakeShoeboxClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
          (fakeShoeboxClient.saveURIs(uris: _*), fakeShoeboxClient)
        }
        val store = mkStore(uris)
        val (_, indexer, _, _, _, _, _, _) = initIndexes(store)
        indexer.isInstanceOf[ShardedArticleIndexer] === true
        indexer.update() === 5 // both subindexer's catch up seqNum = 5
        shoebox.saveURIs(uris(4).withState(NormalizedURIStates.INACTIVE)) // a4
        indexer.update() === 1 // one subindexer's catup seqNum = 6
        indexer.reindex()

        shoebox.saveURIs(uris(2).withState(NormalizedURIStates.ACTIVE),
          NormalizedURI.withHash(title = Some("a5"), normalizedUrl = "http://www.keepit.com/article5", state = SCRAPED))

        indexer.update() === 3 // skipped the active ones. catch up done.
        indexer.catchUpSeqNumber.value === 5 // min of 5 and 6.
        indexer.sequenceNumber.value === 5

        indexer.update() === 3 // 3 uris changed after seqNum 5: a2, a4, a5
        indexer.sequenceNumber.value === 8

      }
    }

    "skip active uris when build index from scratch" in {
      withInjector(helperModules: _*) { implicit injector =>
        val shoebox = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val numUris = 10
        val uris = (0 until numUris).map { n =>
          val state = if (n % 2 == 0) SCRAPED else ACTIVE; NormalizedURI.withHash(title = Some("a" + n),
            normalizedUrl = "http://www.keepit.com/article" + n, state = state)
        }.toList
        val savedUris = shoebox.saveURIs(uris: _*)
        val store = mkStore(savedUris)
        val (_, indexer, _, _, _, _, _, _) = initIndexes(store)
        indexer.isInstanceOf[ShardedArticleIndexer] === true
        indexer.update === 5
        indexer.catchUpSeqNumber.value === 10
        indexer.sequenceNumber.value === 10
      }
    }

  }
}

