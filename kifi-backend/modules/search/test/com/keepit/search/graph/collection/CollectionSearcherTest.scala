package com.keepit.search.graph.collection

import org.specs2.mutable.Specification

import com.google.inject.Singleton
import com.keepit.common.db.Id
import com.keepit.model.Collection
import com.keepit.model.User
import com.keepit.search.graph.GraphTestHelper
import com.keepit.test.DeprecatedEmptyApplication

import play.api.test.Helpers.running


class CollectionSearcherTest extends Specification with GraphTestHelper {
  "collection searcher" should {

    "generate UserToCollectionEdgeSet" in {
      running(new DeprecatedEmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = initData

        val usersWithCollection = users.take(2)
        val expectedUriToUserEdges = uris.map{ (_, usersWithCollection) }
        saveBookmarksByURI(expectedUriToUserEdges)

        val collections = usersWithCollection.foldLeft(Map.empty[Id[User], Collection]){ (m, user) =>
          val coll = saveCollection(user, s"${user.firstName} - Collection")
          val bookmarks = getBookmarksByUser(user.id.get)
          m + (user.id.get -> saveBookmarksToCollection(coll, bookmarks))
        }
        val graph = mkURIGraph()
        graph.update()

        val searcher = graph.getCollectionSearcher()

        val positiveUsers = usersWithCollection.map(_.id.get).toSet
        users.forall{ user =>
          val answer = searcher.getUserToCollectionEdgeSet(user.id.get)
          val expected = collections.get(user.id.get).map(_.id.get).toSet
          answer.destIdSet === expected
          true
        } === true
      }
    }

    "generate UriToCollectionEdgeSet" in {
      running(new DeprecatedEmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = initData

        val expectedUriToUsers = uris.map{ uri => (uri, users.filter( _.id.get.id == uri.id.get.id)) }
        saveBookmarksByURI(expectedUriToUsers)

        val collections = users.foldLeft(Map.empty[User, Collection]){ (m, user) =>
          val coll = saveCollection(user, s"${user.firstName} - Collection")
          val bookmarks = getBookmarksByUser(user.id.get)

          m + (user -> saveBookmarksToCollection(coll, bookmarks))
        }
        val graph = mkURIGraph()
        graph.update()

        val searcher = graph.getCollectionSearcher()

        expectedUriToUsers.forall{ case (uri, users) =>
          val answer = searcher.getUriToCollectionEdgeSet(uri.id.get)
          val expected = users.flatMap{ user => collections.get(user) }.map(_.id.get).toSet
          answer.destIdSet === expected
          true
        } === true
      }
    }

    "generate CollectionToUriEdgeSet" in {
      running(new DeprecatedEmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = initData

        val expectedUriToUsers = uris.map{ uri => (uri, users.filter{ _.id.get.id <= uri.id.get.id }) }
        saveBookmarksByURI(expectedUriToUsers)

        val collections = users.map{ user =>
          val coll = saveCollection(user, s"${user.firstName} - Collection")
          val bookmarks = getBookmarksByUser(user.id.get)

          (saveBookmarksToCollection(coll, bookmarks), bookmarks)
        }
        val graph = mkURIGraph()
        graph.update()

        val searcher = graph.getCollectionSearcher()

        collections.forall{ case (coll, bookmarks) =>
          val answer = searcher.getCollectionToUriEdgeSet(coll.id.get)
          answer.destIdSet === bookmarks.map(_.uriId).toSet
          true
        } === true
      }
    }

    "intersect UserToCollectionEdgeSet and UriToCollectionEdgeSet" in {
      running(new DeprecatedEmptyApplication().withShoeboxServiceModule) {
        val (users, uris) = initData

        val expectedUriToUsers = uris.map{ uri => (uri, users.filter( _.id.get.id == uri.id.get.id)) }
        saveBookmarksByURI(expectedUriToUsers)

        val collections = users.foldLeft(Map.empty[User, Collection]){ (m, user) =>
          val coll = saveCollection(user, s"${user.firstName} - Collection")
          val bookmarks = getBookmarksByUser(user.id.get)

          m + (user -> saveBookmarksToCollection(coll, bookmarks))
        }
        val graph = mkURIGraph()
        graph.update()

        val searcher = graph.getCollectionSearcher()

        expectedUriToUsers.forall{ case (uri, users) =>
          val uriToColl = searcher.getUriToCollectionEdgeSet(uri.id.get)
          val expectedFromUri = users.flatMap{ user => collections.get(user) }.map(_.id.get).toSet
          users.forall{ user =>
            val userToColl = searcher.getUserToCollectionEdgeSet(user.id.get)
            val expectedFromUser = collections.get(user).map(_.id.get).toSet

            searcher.intersect(userToColl, uriToColl).destIdSet === (expectedFromUri intersect expectedFromUser)
            true
          }
        } === true
      }
    }


  }
}