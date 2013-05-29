package com.keepit.search.graph

import com.keepit.search.index.{Searcher, WrappedIndexReader, WrappedSubReader}
import com.keepit.model._
import com.keepit.model.NormalizedURIStates._
import com.keepit.common.db._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.test._
import org.specs2.mutable._
import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._
import org.apache.lucene.index.Term
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import scala.collection.JavaConversions._

class CollectionIndexerTest extends Specification with GraphTestHelper {

  "CollectionIndexer" should {
    "maintain a sequence number on collections " in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB
        val numURIs = uris.size

        val collectionDir = new RAMDirectory
        val collectionIndexer = mkCollectionIndexer(collectionDir)

        val expectedUriToUserEdges = uris.map{ (_, users) } // all users have all uris
        val bookmarks = mkBookmarks(expectedUriToUserEdges)
        collectionIndexer.update() === 0

        val collections = users.zipWithIndex.map{ case (user, idx) =>
          val coll = mkCollection(user, s"${user.firstName} - Collection")
          val bookmark = db.readOnly { implicit s =>
            val uri = uris(idx % numURIs)
            bookmarkRepo.getByUriAndUser(uri.id.get, user.id.get)
          }
          addBookmarks(coll, Seq(bookmark.get))
          coll
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
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB

        val collectionIndexer = mkCollectionIndexer()

        val usersWithCollection = users.take(2)
        val expectedUriToUserEdges = uris.map{ (_, usersWithCollection) }
        mkBookmarks(expectedUriToUserEdges)

        val collections = usersWithCollection.map{ user =>
          val coll = mkCollection(user, s"${user.firstName} - Collection")
          val bookmarks = db.readOnly { implicit s =>
            bookmarkRepo.getByUser(user.id.get)
          }
          addBookmarks(coll, bookmarks)
        }
        collectionIndexer.update()
        collectionIndexer.numDocs === collections.size

        val searcher = collectionIndexer.getSearcher

        val positiveUsers = usersWithCollection.map(_.id.get).toSet
        users.forall{ user =>
          var hits = Set.empty[Long]
          searcher.doSearch(new TermQuery(new Term(CollectionFields.userField, user.id.get.toString))){ (scorer, mapper) =>
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
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB

        val collectionIndexer = mkCollectionIndexer()

        val expectedUriToUsers = uris.map{ uri => (uri, users.filter( _.id.get.id == uri.id.get.id)) }
        mkBookmarks(expectedUriToUsers)

        val collections = users.foldLeft(Map.empty[User, Collection]){ (m, user) =>
          val coll = mkCollection(user, s"${user.firstName} - Collection")
          val bookmarks = db.readOnly { implicit s =>
            bookmarkRepo.getByUser(user.id.get)
          }
          m + (user -> addBookmarks(coll, bookmarks))
        }
        collectionIndexer.update()
        collectionIndexer.numDocs === collections.size

        val searcher = collectionIndexer.getSearcher

        expectedUriToUsers.forall{ case (uri, expectedUsers) =>
          var hits = Set.empty[Long]
          searcher.doSearch(new TermQuery(new Term(CollectionFields.uriField, uri.id.get.toString))){ (scorer, mapper) =>
            var doc = scorer.nextDoc()
            while (doc != NO_MORE_DOCS) {
              hits += mapper.getId(doc)
              doc = scorer.nextDoc()
            }
          }
          hits.size === expectedUsers.size
          expectedUsers.foreach{ u => hits.exists(_ == collections(u).id.get.id) }

          true
        } === true
      }
    }

    "store collection to uri associations in URIList" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (users, uris) = setupDB

        val collectionIndexer = mkCollectionIndexer()

        val expectedUriToUsers = uris.map{ uri => (uri, users.filter{ _.id.get.id <= uri.id.get.id }) }
        mkBookmarks(expectedUriToUsers)

        val collections = users.foldLeft(Map.empty[User, Collection]){ (m, user) =>
          val coll = mkCollection(user, s"${user.firstName} - Collection")
          val bookmarks = db.readOnly { implicit s =>
            bookmarkRepo.getByUser(user.id.get)
          }
          m + (user -> addBookmarks(coll, bookmarks))
        }
        collectionIndexer.update()
        collectionIndexer.numDocs === collections.size

        val searcher = new BaseGraphSearcher(collectionIndexer.getSearcher)

        collections.forall{ case (user, coll) =>
          val uriList = searcher.getURIList(CollectionFields.uriListField, searcher.getDocId(coll.id.get.id))
          (user.id.get, uriList.ids.toSet) === (user.id.get, uris.map(_.id.get.id).filter{ _ >= user.id.get.id }.toSet)
          true
        } === true
      }
    }

    "dump Lucene Document" in {
      running(new DevApplication().withShoeboxServiceModule) {
        val (user, uris, bookmarks, collection) = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Agrajag", lastName = ""))
          val uris = Array(
            uriRepo.save(NormalizedURIFactory(title = "title", url = "http://www.keepit.com/article1", state=SCRAPED)),
            uriRepo.save(NormalizedURIFactory(title = "title", url = "http://www.keepit.com/article2", state=SCRAPED))
          )
          val bookmarks = uris.map{ uri =>
            val url = urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))
            bookmarkRepo.save(BookmarkFactory(title = "line1 titles", url = url,  uriId = uri.id.get, userId = user.id.get, source = BookmarkSource("test")))
          }
          val collection = collectionRepo.save(Collection(userId = user.id.get, name = "CollectionOne"))
          bookmarks.map{ bookmark =>
            keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark.id.get, collectionId = collection.id.get))
          }

          (user, uris, bookmarks, collection)
        }

        val collectionIndexer = mkCollectionIndexer()
        val doc = collectionIndexer.buildIndexable((collection.id.get, user.id.get, SequenceNumber.ZERO)).buildDocument
        doc.getFields.forall{ f => collectionIndexer.getFieldDecoder(f.name).apply(f).length > 0 } === true
      }
    }
  }
}
