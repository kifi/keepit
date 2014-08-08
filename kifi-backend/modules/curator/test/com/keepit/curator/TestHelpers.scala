package com.keepit.curator

import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.model.{ KeepSource, KeepStates, URL, NormalizedURI, Keep, User }
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import model.{ UriScores, UriRecommendation }

object TestHelpers {

  def makeKeeps(userId: Id[User], howMany: Int, shoebox: FakeShoeboxServiceClientImpl): Seq[Keep] = {
    (1 to howMany).flatMap { i =>
      shoebox.saveBookmarks(Keep(
        uriId = Id[NormalizedURI](i),
        urlId = Id[URL](i),
        url = "https://kifi.com",
        userId = userId,
        state = KeepStates.ACTIVE,
        source = KeepSource.keeper,
        libraryId = None))
    }
  }

  def makeUser(num: Int, shoebox: FakeShoeboxServiceClientImpl) =
    shoebox.saveUsers(User(
      id = Some(Id[User](num)),
      firstName = "Some",
      lastName = "User" + num))(0)

  def makeUriRecommendation(uriId: Int, userId: Int, masterScore: Float) =
    UriRecommendation(
      uriId = Id[NormalizedURI](uriId),
      userId = Id[User](userId),
      masterScore = masterScore,
      allScores = UriScores(socialScore = 1.0f,
        popularityScore = 1.0f,
        overallInterestScore = 1.0f,
        recentInterestScore = 1.0f,
        recencyScore = 1.0f,
        priorScore = 1.0f,
        rekeepScore = 1.0f,
        discoveryScore = 1.0f),
      seen = false, clicked = false, kept = false)
}
