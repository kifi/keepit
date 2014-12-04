package com.keepit.search.result

import org.specs2.mutable.Specification
import com.keepit.search.SearchConfig
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.Scoring
import scala.math.pow

class ResultMergerTest extends Specification {

  val detailedSearchHit1 = {
    DetailedSearchHit(
      uriId = 1,
      bookmarkCount = 2,
      hit = BasicSearchHit(title = None, url = "http://hit1.com"),
      isMyBookmark = true,
      isFriendsBookmark = true,
      isPrivate = false,
      users = Seq(Id[User](1), Id[User](2)),
      score = 3f,
      textScore = 3f,
      scoring = new Scoring(3f, 3f, 0f, 0f, false)
    )
  }

  val detailedSearchHit2 = {
    DetailedSearchHit(
      uriId = 2,
      bookmarkCount = 1,
      hit = BasicSearchHit(title = Some("hit2"), url = "http://hit2.com"),
      isMyBookmark = false,
      isFriendsBookmark = true,
      isPrivate = false,
      users = Seq(Id[User](2)),
      score = 2f,
      textScore = 2f,
      scoring = new Scoring(2f, 2f, 0f, 0f, false)
    )
  }

  val detailedSearchHit3 = {
    DetailedSearchHit(
      uriId = 3,
      bookmarkCount = 2,
      hit = BasicSearchHit(title = None, url = "http://hit3.com"),
      isMyBookmark = false,
      isFriendsBookmark = true,
      isPrivate = false,
      users = Seq(Id[User](2), Id[User](3)),
      score = 1f,
      textScore = 1f,
      scoring = new Scoring(1f, 1f, 0f, 0f, false)
    )
  }

  val shardSearchResult1 = {
    PartialSearchResult(
      hits = Seq(detailedSearchHit1, detailedSearchHit2),
      myTotal = 1,
      friendsTotal = 2,
      othersTotal = 0,
      friendStats = FriendStats(ids = Array(2L), scores = Array(2.4f)),
      show = true,
      cutPoint = 2
    )
  }

  val shardSearchResult2 = {
    PartialSearchResult(
      hits = Seq(detailedSearchHit3),
      myTotal = 0,
      friendsTotal = 1,
      othersTotal = 0,
      friendStats = FriendStats(ids = Array(2L, 3L), scores = Array(2f, 1.8f)),
      show = true,
      cutPoint = 1
    )
  }

  val shardResults = Seq(shardSearchResult1, shardSearchResult2)

  val bookmarkBoost = SearchConfig.defaultConfig.asFloat("myBookmarkBoost")

  val mergedDetailedSearchHit1 = {
    DetailedSearchHit(
      uriId = 1,
      bookmarkCount = 2,
      hit = BasicSearchHit(title = None, url = "http://hit1.com"),
      isMyBookmark = true,
      isFriendsBookmark = true,
      isPrivate = false,
      users = Seq(Id[User](1), Id[User](2)),
      score = 3f / 3f * bookmarkBoost,
      textScore = 3f,
      scoring = new Scoring(3f, 3f, 0f, 0f, false)
    )
  }

  val mergedDetailedSearchHit2 = {
    DetailedSearchHit(
      uriId = 2,
      bookmarkCount = 1,
      hit = BasicSearchHit(title = Some("hit2"), url = "http://hit2.com"),
      isMyBookmark = false,
      isFriendsBookmark = true,
      isPrivate = false,
      users = Seq(Id[User](2)),
      score = 2f / 3f,
      textScore = 2f,
      scoring = new Scoring(2f, 2f, 0f, 0f, false)
    )
  }

  val mergedDetailedSearchHits = {
    List(mergedDetailedSearchHit1, mergedDetailedSearchHit2)
  }

  val expectedMerge = PartialSearchResult(
    hits = mergedDetailedSearchHits,
    myTotal = 1,
    friendsTotal = 3,
    othersTotal = 0,
    friendStats = FriendStats(Array(2L, 3L), Array(4.4f, 1.8f)),
    show = true,
    cutPoint = mergedDetailedSearchHits.length
  )

  "result merger" should {
    "work" in {
      val resultMerger = new ResultMerger(enableTailCutting = false, config = SearchConfig.defaultConfig, true)
      val merged = resultMerger.merge(shardResults, maxHits = 2)

      merged.hits === expectedMerge.hits
      merged.myTotal === expectedMerge.myTotal
      merged.friendsTotal === expectedMerge.friendsTotal
      merged.othersTotal === expectedMerge.othersTotal
      merged.friendStats.ids === expectedMerge.friendStats.ids
      merged.friendStats.scores === expectedMerge.friendStats.scores
      merged.show === expectedMerge.show
    }
  }
}
