package com.keepit.search.index.graph.collection

import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.common.strings._
import com.keepit.search.index.article.ArticleFields
import com.keepit.search.test.SearchTestInjector
import org.specs2.mutable._
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.TermQuery
import scala.collection.JavaConversions._
import com.keepit.search.index.{ Indexable, VolatileIndexDirectory }
import com.keepit.search.index.graph.BaseGraphSearcher
import com.keepit.search.index.graph.GraphTestHelper
import com.keepit.common.util.PlayAppConfigurationModule

class CollectionIndexerTest extends Specification with SearchTestInjector with GraphTestHelper {

  val helperModules = Seq(PlayAppConfigurationModule())

  "CollectionIndexer" should {
    "maintain a sequence number on collections " in {
      withInjector(helperModules: _*) { implicit injector =>
        val (users, uris) = initData
        val numURIs = uris.size

        val collectionDir = new VolatileIndexDirectory()
        val collectionIndexer = mkCollectionIndexer(collectionDir)

        val expectedUriToUserEdges = uris.map { (_, users) } // all users have all uris
        val allKeeps = saveBookmarksByURI(expectedUriToUserEdges)
        collectionIndexer.update() === 0

        val collections = users.zipWithIndex.map {
          case (user, idx) =>
            val coll = saveCollection(user, s"${user.firstName} - Collection")
            val bookmark = {
              val uri = uris(idx % numURIs)
              allKeeps.find { keep => keep.uriId == uri.id.get && keep.userId == user.id.get }
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
      withInjector(helperModules: _*) { implicit injector =>
        val (users, uris) = initData

        val collectionIndexer = mkCollectionIndexer()

        val usersWithCollection = users.take(2)
        val expectedUriToUserEdges = uris.map { (_, usersWithCollection) }
        saveBookmarksByURI(expectedUriToUserEdges)

        val collections = usersWithCollection.map { user =>
          val coll = saveCollection(user, s"${user.firstName} - Collection")
          val bookmarks = getBookmarksByUser(user.id.get)
          saveBookmarksToCollection(coll, bookmarks)
        }
        collectionIndexer.update()
        collectionIndexer.numDocs === collections.size

        val searcher = collectionIndexer.getSearcher

        val positiveUsers = usersWithCollection.map(_.id.get).toSet
        users.forall { user =>
          var hits = Set.empty[Long]
          searcher.search(new TermQuery(new Term(CollectionFields.userField, user.id.get.toString))) { (scorer, reader) =>
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
      withInjector(helperModules: _*) { implicit injector =>
        val (users, uris) = initData

        val collectionIndexer = mkCollectionIndexer()

        val expectedUriToUsers = uris.map { uri => (uri, users.filter(_.id.get.id == uri.id.get.id)) }
        saveBookmarksByURI(expectedUriToUsers)

        val collections = users.foldLeft(Map.empty[User, Collection]) { (m, user) =>
          val coll = saveCollection(user, s"${user.firstName} - Collection")
          val bookmarks = getBookmarksByUser(user.id.get)
          m + (user -> saveBookmarksToCollection(coll, bookmarks))
        }
        collectionIndexer.update()
        collectionIndexer.numDocs === collections.size

        val searcher = collectionIndexer.getSearcher

        expectedUriToUsers.forall {
          case (uri, expectedUsers) =>
            var hits = Set.empty[Long]
            searcher.search(new TermQuery(new Term(CollectionFields.uriField, uri.id.get.toString))) { (scorer, reader) =>
              val mapper = reader.getIdMapper
              var doc = scorer.nextDoc()
              while (doc != NO_MORE_DOCS) {
                hits += mapper.getId(doc)
                doc = scorer.nextDoc()
              }
            }
            hits.size === expectedUsers.size
            expectedUsers.foreach { u => hits.exists(_ == collections(u).id.get.id) === true }

            true
        } === true
      }
    }

    "store collection to uri associations in URIList" in {
      withInjector(helperModules: _*) { implicit injector =>
        val (users, uris) = initData

        val collectionIndexer = mkCollectionIndexer()

        val expectedUriToUsers = uris.map { uri => (uri, users.filter { _.id.get.id <= uri.id.get.id }) }
        saveBookmarksByURI(expectedUriToUsers)

        val collections = users.map { user =>
          val coll = saveCollection(user, s"${user.firstName} - Collection")
          val bookmarks = getBookmarksByUser(user.id.get)
          (user, saveBookmarksToCollection(coll, bookmarks), bookmarks)
        }
        collectionIndexer.update()
        collectionIndexer.numDocs === collections.size

        val searcher = new BaseGraphSearcher(collectionIndexer.getSearcher)

        collections.forall {
          case (user, coll, bookmarks) =>
            val uriList = searcher.getURIList(CollectionFields.uriListField, searcher.getDocId(coll.id.get.id))
            (user.id.get, uriList.ids.toSet) === (user.id.get, bookmarks.map(_.uriId.id).toSet)
            true
        } === true
      }
    }

    "store colleciton external ids" in {
      withInjector(helperModules: _*) { implicit injector =>
        val (users, uris) = initData

        val collectionIndexer = mkCollectionIndexer()

        val expectedUriToUsers = uris.map { uri => (uri, users.filter { _.id.get.id <= uri.id.get.id }) }
        saveBookmarksByURI(expectedUriToUsers)

        val collections = users.map { user =>
          val coll = saveCollection(user, s"${user.firstName} - Collection")
          val bookmarks = getBookmarksByUser(user.id.get)
          (user, saveBookmarksToCollection(coll, bookmarks), bookmarks)
        }
        collectionIndexer.update()
        collectionIndexer.numDocs === collections.size

        val searcher = collectionIndexer.getSearcher

        collections.forall {
          case (user, coll, bookmarks) =>
            val extIdOpt = searcher.getDecodedDocValue[String](CollectionFields.externalIdField, coll.id.get.id)(fromByteArray)
            extIdOpt must beSome[String]
            extIdOpt.get === coll.externalId.id
            true
        } === true
      }
    }

    "dump Lucene Document" in {
      withInjector(helperModules: _*) { implicit injector =>
        val Seq(user) = saveUsers(UserFactory.user().withName("Agrajag", "").withUsername("test").get)
        val uris = saveURIs(
          NormalizedURI.withHash(title = Some("title"), normalizedUrl = "http://www.keepit.com/article1").withContentRequest(true),
          NormalizedURI.withHash(title = Some("title"), normalizedUrl = "http://www.keepit.com/article2").withContentRequest(true)
        )
        val bookmarks = saveBookmarksByUser(Seq((user, uris)), uniqueTitle = Some("line1 titles"))
        val collection = saveCollection(user, "CollectionOne")
        saveBookmarksToCollection(collection, bookmarks)

        val collectionIndexer = mkCollectionIndexer()
        val (col, bm) = CollectionIndexer.fetchData(collection.id.get, user.id.get, shoeboxClient)
        val doc = collectionIndexer.buildIndexable(col, bm).buildDocument
        doc.getFields.forall { f => Indexable.getFieldDecoder(CollectionFields.decoders)(f.name).apply(f).length > 0 } === true
      }
    }
  }
}
