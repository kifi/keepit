package com.keepit.search.sharding

import org.specs2.mutable._
import play.api.test.Helpers._
import play.api.Play.current
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.inject._
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.model.User
import com.keepit.search.graph.bookmark._
import com.keepit.search.graph.collection._
import com.keepit.search.SearchTestHelper
import com.keepit.search.SearchFilter
import com.keepit.test._
import scala.math._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.shoebox.FakeShoeboxServiceClientImpl

class IndexShardingTest extends Specification with SearchApplicationInjector with SearchTestHelper {

  implicit private val activeShards =  (new ActiveShardsSpecParser).parse(Some("0,1/2"))

  "ShardedArticleIndexer" should {
    "create index shards" in {
      running(application) {
        val (users, uris) = initData(numUsers = 9, numUris = 9)
        val expectedUriToUserEdges = uris.take(9).toIterator.zip((1 to 9).iterator.map(users.take(_))).toList
        val bookmarks = saveBookmarksByURI(expectedUriToUserEdges)

        val collKeeps = Seq(
          bookmarks.filter{ b => b.userId == users(0).id.get && b.uriId.id % 3 == 0 },
          bookmarks.filter{ b => b.userId == users(1).id.get && b.uriId.id % 3 == 1 },
          bookmarks.filter{ b => b.userId == users(1).id.get && b.uriId.id % 3 == 2 }
        )
        val collections = collKeeps.zipWithIndex.map{ case (s, i) =>
          val Seq(collection) = saveCollections(Collection(userId = users(i).id.get, name = s"coll $i"))
          saveBookmarksToCollection(collection.id.get, s:_*)
          collection
        }

        val store = mkStore(uris)
        val (uriGraph, collectionGraph, indexer, mainSearcherFactory) = initIndexes(store)

        indexer.isInstanceOf[ShardedArticleIndexer] === true
        uriGraph.isInstanceOf[ShardedURIGraphIndexer] === true
        collectionGraph.isInstanceOf[ShardedCollectionIndexer] === true

        uriGraph.update()
        collectionGraph.update()
        indexer.update() === uris.size

        users.foreach{ user =>
          val userId = user.id.get
          activeShards.shards.foreach{ shard =>
            val mainSearcher = mainSearcherFactory(shard, userId, "alldocs", english, 1000, SearchFilter.default(), allHitsConfig)
            val uriGraphSearcher = mainSearcher.uriGraphSearcher
            val collectionSearcher = mainSearcher.collectionSearcher

            uris.foreach{ uri =>
              mainSearcher.getArticleRecord(uri.id.get).isDefined === shard.contains(uri.id.get)
            }

            val keeps = uriGraphSearcher.getUserToUriEdgeSet(userId).destIdSet
            keeps.forall(shard.contains(_)) === true

            bookmarks.filter(_.userId == userId).foreach{ bookmark =>
              if (shard.contains(bookmark.uriId)) {
                keeps.contains(bookmark.uriId) === true
                mainSearcher.getBookmarkRecord(bookmark.uriId).isDefined === true
              } else {
                keeps.contains(bookmark.uriId) === false
              }
            }
            collections.foreach{ collection =>
              collectionSearcher.getCollectionToUriEdgeSet(collection.id.get).destIdSet.forall(shard.contains(_)) === true

              val urisInCollection = getUriIdsInCollection(collection.id.get).map(_.uriId)
              collectionSearcher.getName(collection.id.get) === (if (urisInCollection.exists(shard.contains(_))) collection.name else "")
            }
          }

          val keeps = bookmarks.filter( _.userId == userId).map(_.uriId).toSet
          val shardedKeeps = activeShards.shards.map{ shard =>
            val uriGraphSearcher = mainSearcherFactory.getURIGraphSearcher(shard, userId)

            uriGraphSearcher.getUserToUriEdgeSet(userId).destIdSet
          }
          shardedKeeps.map(_.size).sum === keeps.size
          shardedKeeps.reduce(_ union _) === keeps
        }

        (collections zip collKeeps).foreach{ case (collection, collKeeps) =>
          val shardedKeeps = activeShards.shards.map{ shard =>
            val collectionSearcher = CollectionSearcher(collectionGraph.getIndexer(shard))
            collectionSearcher.getCollectionToUriEdgeSet(collection.id.get).destIdSet
          }
          val keeps = collKeeps.map(_.uriId).toSet
          shardedKeeps.map(_.size).sum === keeps.size
          shardedKeeps.reduce(_ union _) === keeps
        }
      }
    }

    "handle URI migration" in {
      running(application) {
        val (Seq(user), uris) = initData(numUsers = 1, numUris = 20)
        val userId = user.id.get
        val expectedUriToUserEdges = uris.take(5).map(_ -> Seq(user))
        val bookmarks = saveBookmarksByURI(expectedUriToUserEdges)
        val collection = {
          val Seq(collection) = saveCollections(Collection(userId = userId, name = "mutating collection"))
          saveBookmarksToCollection(collection.id.get, bookmarks:_*)
          collection
        }

        val store = mkStore(uris)
        val (uriGraph, collectionGraph, indexer, mainSearcherFactory) = initIndexes(store)

        indexer.isInstanceOf[ShardedArticleIndexer] === true
        uriGraph.isInstanceOf[ShardedURIGraphIndexer] === true
        collectionGraph.isInstanceOf[ShardedCollectionIndexer] === true

        uriGraph.update()
        collectionGraph.update()

        val originalBookmark = bookmarks.find(_.userId == userId).get
        val sourceShard = activeShards.shards.find(_.contains(originalBookmark.uriId)).get
        val targetShard = activeShards.shards.find(! _.contains(originalBookmark.uriId)).get
        val targetUri = uris.filter(u => targetShard.contains(u.id.get)).find(u => !bookmarks.exists(k => k.uriId == u.id.get)).get

        uriGraph.update()
        collectionGraph.update()
        mainSearcherFactory.clear()

        def getKeepSize(shard: Shard[NormalizedURI]) = mainSearcherFactory.getURIGraphSearcher(shard, userId).getUserToUriEdgeSet(userId).size
        def getCollectionSize(shard: Shard[NormalizedURI]) = mainSearcherFactory.getCollectionSearcher(shard, userId).getCollectionToUriEdgeSet(collection.id.get).size

        val oldKeepSizes = activeShards.shards.map{ shard => (shard -> getKeepSize(shard)) }.toMap
        val oldCollSizes = activeShards.shards.map{ shard => (shard -> getCollectionSize(shard)) }.toMap

        // migrate URI
        val Seq(migratedBookmark) = saveBookmarks(originalBookmark.copy(uriId = targetUri.id.get))
        saveCollections(collection)

        uriGraph.update()
        collectionGraph.update()
        mainSearcherFactory.clear() // remove cached searchers

        val newKeepSizes = activeShards.shards.map{ shard => (shard -> getKeepSize(shard)) }.toMap
        val newCollSizes = activeShards.shards.map{ shard => (shard -> getCollectionSize(shard)) }.toMap

        activeShards.shards.foreach{ shard =>
          val mainSearcher = mainSearcherFactory(shard, userId, "alldocs", english, 1000, SearchFilter.default(), allHitsConfig)
          val uriGraphSearcher = mainSearcher.uriGraphSearcher
          val collectionSearcher = mainSearcher.collectionSearcher

          if (shard == sourceShard) {
            uriGraphSearcher.getUserToUriEdgeSet(userId).destIdSet.contains(originalBookmark.uriId) === false
            collectionSearcher.getCollectionToUriEdgeSet(collection.id.get).destIdSet.contains(originalBookmark.uriId) === false

            newKeepSizes(shard) === oldKeepSizes(shard) - 1
            newCollSizes(shard) === oldCollSizes(shard) - 1
          } else if (shard == targetShard) {
            uriGraphSearcher.getUserToUriEdgeSet(userId).destIdSet.contains(migratedBookmark.uriId) === true
            mainSearcher.getBookmarkRecord(migratedBookmark.uriId).isDefined === true
            collectionSearcher.getCollectionToUriEdgeSet(collection.id.get).destIdSet.contains(migratedBookmark.uriId) === true

            newKeepSizes(shard) === oldKeepSizes(shard) + 1
            newCollSizes(shard) === oldCollSizes(shard) + 1
          } else {
            newKeepSizes(shard) === oldKeepSizes(shard)
            newCollSizes(shard) === oldCollSizes(shard)
          }
        }
      }
    }

    "correctly reindex" in {
       running(application){
        val numUris = 5
        val (uris, shoebox) = {
          val uris =   (0 until numUris).map {n => NormalizedURI.withHash(title = Some("a" + n),
          normalizedUrl = "http://www.keepit.com/article" + n, state = SCRAPED)}.toList
          val fakeShoeboxClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
          (fakeShoeboxClient.saveURIs(uris:_*), fakeShoeboxClient)
        }
        val store = mkStore(uris)
        val (uriGraph, collectionGraph, indexer, mainSearcherFactory) = initIndexes(store)
        indexer.isInstanceOf[ShardedArticleIndexer] === true
        indexer.update() === 5
        shoebox.saveURIs(uris(4).withState(NormalizedURIStates.INACTIVE))   // a4
        indexer.update() === 1
        indexer.reindex()

        shoebox.saveURIs(uris(2).withState(NormalizedURIStates.ACTIVE),
            NormalizedURI.withHash(title = Some("a5"), normalizedUrl = "http://www.keepit.com/article5", state = SCRAPED)  )

        indexer.update() === 3      // skipped the active ones. catch up done.
        indexer.catchUpSeqNumber.value === 4   // min of 4 and 6.
        indexer.sequenceNumber.value === 4

        indexer.update() === 3     // 3 uris changed after seqNum 4: a2, a4, a5
        indexer.sequenceNumber.value === 8

      }
    }

  }
}

