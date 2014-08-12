package com.keepit.curator

import com.google.inject.{ Module, Injector }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.model._
import com.keepit.shoebox.{ ShoeboxServiceClient, ShoeboxScraperClient, FakeShoeboxServiceClientImpl }
import model.{ UriRecommendationRepo, UriRecommendationStates, UriScores, UriRecommendation }

trait CuratorTestHelpers { this: CuratorTestInjector =>

  def shoeboxClientInstance()(implicit injector: Injector) = {
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
  }

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

  def makeKeepsWithPrivacy(userId: Id[User], howMany: Int, isPrivate: Boolean, shoebox: FakeShoeboxServiceClientImpl): Seq[Keep] = {
    (1 to howMany).flatMap { i =>
      shoebox.saveBookmarks(Keep(
        uriId = Id[NormalizedURI](i),
        urlId = Id[URL](i),
        url = "https://kifi.com",
        userId = userId,
        state = KeepStates.ACTIVE,
        source = KeepSource.keeper,
        isPrivate = isPrivate,
        libraryId = None))
    }
  }

  def makeUser(num: Int, shoebox: FakeShoeboxServiceClientImpl) =
    shoebox.saveUsers(User(
      id = Some(Id[User](num)),
      firstName = "Some",
      lastName = "User" + num,
      primaryEmail = Some(EmailAddress(s"user$num@kifi.com"))))(0)

  def makeNormalizedUri(id: Int, url: String) =
    NormalizedURI(
      id = Some(Id[NormalizedURI](id)),
      urlHash = UrlHash(url),
      url = url
    )

  def makeUriRecommendation(uriId: Int, userId: Int, masterScore: Float) =
    UriRecommendation(
      uriId = Id[NormalizedURI](uriId),
      userId = Id[User](userId),
      masterScore = masterScore,
      state = UriRecommendationStates.ACTIVE,
      allScores = UriScores(socialScore = 1.0f,
        popularityScore = 1.0f,
        overallInterestScore = 1.0f,
        recentInterestScore = 1.0f,
        recencyScore = 1.0f,
        priorScore = 1.0f,
        rekeepScore = 1.0f,
        discoveryScore = 1.0f),
      seen = false, clicked = false, kept = false)

  def makeCompleteUriRecommendation(uriId: Int, userId: Int, masterScore: Float, url: String) = {
    val normalizedUri = makeNormalizedUri(uriId, url)
    val uriRecommendation = makeUriRecommendation(uriId, userId, masterScore)
    val uriSummary = URISummary(
      title = Some("Test"),
      description = Some("Description "),
      imageUrl = Some(s"image.jpg")
    )

    (normalizedUri, uriRecommendation, uriSummary)
  }

  def saveUriModels(tuple: (NormalizedURI, UriRecommendation, URISummary),
    shoebox: FakeShoeboxServiceClientImpl)(implicit injector: Injector, rw: RWSession) = {
    val uriRecoRepo = inject[UriRecommendationRepo]
    val (uri, uriReco, uriSumm) = tuple
    val savedUri = shoebox.saveURIs(uri).head
    val savedUriReco = uriRecoRepo.save(uriReco)
    (savedUri, savedUriReco, shoebox.saveURISummary(savedUri.id.get, uriSumm))
  }
}
