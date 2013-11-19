package com.keepit.search.graph

import com.keepit.inject.ApplicationInjector
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.common._
import com.keepit.common.cache.TestCacheModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.strings._
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.query.parser.TermIterator
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test._
import org.specs2.mutable._
import play.api.test.Helpers._
import org.apache.lucene.index.Term
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.TermQuery
import scala.collection.JavaConversions._
import com.keepit.search.index.VolatileIndexDirectoryImpl

class CollectionIndexerTest extends Specification with ApplicationInjector with GraphTestHelper {

  "CollectionIndexer" should {
    "maintain a sequence number on collections " in {
      running(new TestApplication(FakeHttpClientModule(), TestCacheModule(), FakeShoeboxServiceModule())) {
        val (users, uris) = initData
        val numURIs = uris.size

        val collectionDir = new VolatileIndexDirectoryImpl()
        val collectionIndexer = mkCollectionIndexer(collectionDir)

        val expectedUriToUserEdges = uris.map{ (_, users) } // all users have all uris
        saveBookmarksByURI(expectedUriToUserEdges)
        collectionIndexer.update() === 0

        val collections = users.zipWithIndex.map{ case (user, idx) =>
          val coll = saveCollection(user, s"${user.firstName} - Collection")
          val bookmark = {
            val uri = uris(idx % numURIs)
            getBookmarkByUriAndUser(uri.id.get, user.id.get)
          }
          saveBookmarksToCollection(coll, Seq(bookmark.get))
        }
        collectionIndexer.update() === collections.size
        collectionIndexer.sequenceNumber.value === (collections.size * 2)
        collectionIndexer.numDocs === collections.size
        collectionIndexer.close()

        val collectionIndexer2 = mkCollectionIndexer(collectionDir)
        collectionIndexer2.sequenceNumber.value === (collections.size * 2)
        collectionIndexer2.numDocs === collections.size
      }
    }

    "find collections by user" in {
      running(new TestApplication(FakeHttpClientModule(), TestCacheModule(), FakeShoeboxServiceModule())) {
        val (users, uris) = initData

        val collectionIndexer = mkCollectionIndexer()

        val usersWithCollection = users.take(2)
        val expectedUriToUserEdges = uris.map{ (_, usersWithCollection) }
        saveBookmarksByURI(expectedUriToUserEdges)

        val collections = usersWithCollection.map{ user =>
          val coll = saveCollection(user, s"${user.firstName} - Collection")
          val bookmarks = getBookmarksByUser(user.id.get)
          saveBookmarksToCollection(coll, bookmarks)
        }
        collectionIndexer.update()
        collectionIndexer.numDocs === collections.size

        val searcher = collectionIndexer.getSearcher

        val positiveUsers = usersWithCollection.map(_.id.get).toSet
        users.forall{ user =>
          var hits = Set.empty[Long]
          searcher.doSearch(new TermQuery(new Term(CollectionFields.userField, user.id.get.toString))){ (scorer, reader) =>
            val mapper = reader.getIdMapper
            var doc = scorer.nextDoc()
            while (doc != NO_MORE_DOCS) {
              hits += mapper.getId(doc)
              doc = scorer.nextDoc()
            }
          }
          hits.size === (if (positiveUsers.contains(user.id.get)) 1 else 0)
          true
        } === true
      }
    }

    "find collections by uri" in {
      running(new TestApplication(FakeHttpClientModule(), TestCacheModule(), FakeShoeboxServiceModule())) {
        val (users, uris) = initData

        val collectionIndexer = mkCollectionIndexer()

        val expectedUriToUsers = uris.map{ uri => (uri, users.filter( _.id.get.id == uri.id.get.id)) }
        saveBookmarksByURI(expectedUriToUsers)

        val collections = users.foldLeft(Map.empty[User, Collection]){ (m, user) =>
          val coll = saveCollection(user, s"${user.firstName} - Collection")
          val bookmarks = getBookmarksByUser(user.id.get)
          m + (user -> saveBookmarksToCollection(coll, bookmarks))
        }
        collectionIndexer.update()
        collectionIndexer.numDocs === collections.size

        val searcher = collectionIndexer.getSearcher

        expectedUriToUsers.forall{ case (uri, expectedUsers) =>
          var hits = Set.empty[Long]
          searcher.doSearch(new TermQuery(new Term(CollectionFields.uriField, uri.id.get.toString))){ (scorer, reader) =>
            val mapper = reader.getIdMapper
            var doc = scorer.nextDoc()
            while (doc != NO_MORE_DOCS) {
              hits += mapper.getId(doc)
              doc = scorer.nextDoc()
            }
          }
          hits.size === expectedUsers.size
          expectedUsers.foreach{ u => hits.exists(_ == collections(u).id.get.id) === true }

          true
        } === true
      }
    }

    "store collection to uri associations in URIList" in {
      running(new TestApplication(FakeHttpClientModule(), TestCacheModule(), FakeShoeboxServiceModule())) {
        val (users, uris) = initData

        val collectionIndexer = mkCollectionIndexer()

        val expectedUriToUsers = uris.map{ uri => (uri, users.filter{ _.id.get.id <= uri.id.get.id }) }
        saveBookmarksByURI(expectedUriToUsers)

        val collections = users.map{ user =>
          val coll = saveCollection(user, s"${user.firstName} - Collection")
          val bookmarks = getBookmarksByUser(user.id.get)
          (user, saveBookmarksToCollection(coll, bookmarks), bookmarks)
        }
        collectionIndexer.update()
        collectionIndexer.numDocs === collections.size

        val searcher = new BaseGraphSearcher(collectionIndexer.getSearcher)

        collections.forall{ case (user, coll, bookmarks) =>
          val uriList = searcher.getURIList(CollectionFields.uriListField, searcher.getDocId(coll.id.get.id))
          (user.id.get, uriList.ids.toSet) === (user.id.get, bookmarks.map(_.uriId.id).toSet)
          true
        } === true
      }
    }

    "store colleciton external ids" in {
      running(new TestApplication(FakeHttpClientModule(), TestCacheModule(), FakeShoeboxServiceModule())) {
        val (users, uris) = initData

        val collectionIndexer = mkCollectionIndexer()

        val expectedUriToUsers = uris.map{ uri => (uri, users.filter{ _.id.get.id <= uri.id.get.id }) }
        saveBookmarksByURI(expectedUriToUsers)

        val collections = users.map{ user =>
          val coll = saveCollection(user, s"${user.firstName} - Collection")
          val bookmarks = getBookmarksByUser(user.id.get)
          (user, saveBookmarksToCollection(coll, bookmarks), bookmarks)
        }
        collectionIndexer.update()
        collectionIndexer.numDocs === collections.size

        val searcher = collectionIndexer.getSearcher

        collections.forall{ case (user, coll, bookmarks) =>
          val extIdOpt = searcher.getDecodedDocValue[String](CollectionFields.externalIdField, coll.id.get.id)(fromByteArray)
          extIdOpt must beSome[String]
          extIdOpt.get === coll.externalId.id
          true
        } === true
      }
    }

    "detect collection names" in {
      running(new TestApplication(FakeHttpClientModule(), TestCacheModule(), FakeShoeboxServiceModule())) {
        val (users, uris) = initData

        val collectionIndexer = mkCollectionIndexer()

        val expectedUriToUsers = uris.map{ uri => (uri, users.filter{ _.id.get.id <= uri.id.get.id }) }
        saveBookmarksByURI(expectedUriToUsers)

        val user = users.head
        val bookmarks = getBookmarksByUser(user.id.get)
        val collNames = Seq("Design", "Flat Design", "Web Design", "Design Web", "Web")
        val collections = (collNames zip bookmarks).map{ case (name, bookmark) =>
          saveCollection(user, name) tap { saveBookmarksToCollection(_, Seq(bookmark)) }
        }
        collectionIndexer.update()
        collectionIndexer.numDocs === collections.size

        val (collectionIndexSearcher, collectionNameIndexSearcher) = collectionIndexer.getSearchers
        val searcher = new CollectionSearcherWithUser(collectionIndexSearcher, collectionNameIndexSearcher, user.id.get)

        def getCollectionId(name: String): Long = {
          collections.find(_.name == name).get.id.get.id
        }
        def detect(text: String) = {
          val stemmedTermSeq = new TermIterator("", text, DefaultAnalyzer.forParsingWithStemmer).toIndexedSeq
          searcher.detectCollectionNames(stemmedTermSeq)
        }
        detect("design") === Set((0, 1, getCollectionId("Design")))

        detect("designs") === Set((0, 1, getCollectionId("Design")))

        detect("flat") === Set()

        detect("flat design") === Set((1, 1, getCollectionId("Design")), (0, 2, getCollectionId("Flat Design")))

        detect("web design") === Set((0, 1, getCollectionId("Web")), (1, 1, getCollectionId("Design")), (0, 2, getCollectionId("Web Design")))

        detect("boring flat design movement") === Set((2, 1, getCollectionId("Design")), (1, 2, getCollectionId("Flat Design")))
      }
    }

    "dump Lucene Document" in {
      running(new TestApplication(FakeHttpClientModule(), TestCacheModule(), FakeShoeboxServiceModule())) {
        val Seq(user) = saveUsers(User(firstName = "Agrajag", lastName = ""))
        val uris = saveURIs(
          NormalizedURI.withHash(title = Some("title"), normalizedUrl = "http://www.keepit.com/article1", state = SCRAPED),
          NormalizedURI.withHash(title = Some("title"), normalizedUrl = "http://www.keepit.com/article2", state = SCRAPED)
        )
        val bookmarks = saveBookmarksByUser(Seq((user, uris)), uniqueTitle = Some("line1 titles"))
        val collection = saveCollection(user, "CollectionOne")
        saveBookmarksToCollection(collection, bookmarks)

        val collectionIndexer = mkCollectionIndexer()
        val doc = collectionIndexer.buildIndexable(collection).buildDocument
        doc.getFields.forall{ f => collectionIndexer.getFieldDecoder(f.name).apply(f).length > 0 } === true
      }
    }
  }
}
