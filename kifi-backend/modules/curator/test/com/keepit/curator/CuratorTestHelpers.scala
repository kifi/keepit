package com.keepit.curator

import com.google.inject.{ Module, Injector }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.curator.model._
import com.keepit.model._
import com.keepit.shoebox.{ ShoeboxServiceClient, ShoeboxScraperClient, FakeShoeboxServiceClientImpl }
import org.joda.time.DateTime
import com.keepit.common.time._

import scala.collection.mutable.ListBuffer

trait CuratorTestHelpers { this: CuratorTestInjector =>

  val userKeepAttributions = collection.mutable.Map[Id[User], (Seq[User], Int)]()

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
        visibility = LibraryVisibility.DISCOVERABLE,
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
        visibility = LibraryVisibility.SECRET,
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

  val defaultAllScores = UriScores(socialScore = 1.0f,
    popularityScore = 1.0f,
    overallInterestScore = 1.0f,
    recentInterestScore = 1.0f,
    recencyScore = 1.0f,
    priorScore = 1.0f,
    rekeepScore = 1.0f,
    curationScore = None,
    multiplier = Some(1.0f),
    discoveryScore = 1.0f)

  def makeUriRecommendation(uriId: Int, userIdInt: Int, masterScore: Float, allScores: UriScores = defaultAllScores) = {
    val userId = Id[User](userIdInt)
    UriRecommendation(
      uriId = Id[NormalizedURI](uriId),
      userId = userId,
      masterScore = masterScore,
      state = UriRecommendationStates.ACTIVE,
      allScores = allScores,
      delivered = 0, clicked = 0, kept = false,
      attribution = makeSeedAttribution(userId))
  }

  def makeUriRecommendationWithUpdateTimestamp(uriId: Int, userIdInt: Int, masterScore: Float, updatedAt: DateTime) = {
    val userId = Id[User](userIdInt)
    UriRecommendation(
      createdAt = updatedAt,
      updatedAt = updatedAt,
      uriId = Id[NormalizedURI](uriId),
      userId = userId,
      masterScore = masterScore,
      state = UriRecommendationStates.ACTIVE,
      allScores = UriScores(socialScore = 1.0f,
        popularityScore = 1.0f,
        overallInterestScore = 1.0f,
        recentInterestScore = 1.0f,
        recencyScore = 1.0f,
        priorScore = 1.0f,
        rekeepScore = 1.0f,
        curationScore = None,
        multiplier = Some(1.0f),
        discoveryScore = 1.0f),
      delivered = 0, clicked = 0, kept = false,
      attribution = makeSeedAttribution(userId))
  }

  def makePublicFeed(uriId: Int, publicMasterScore: Float) = {
    PublicFeed(
      uriId = Id[NormalizedURI](uriId),
      publicMasterScore = publicMasterScore,
      state = PublicFeedStates.ACTIVE,
      publicAllScores = PublicUriScores(
        popularityScore = 1.0f,
        recencyScore = 1.0f,
        rekeepScore = 1.0f,
        curationScore = Some(1.0f),
        multiplier = Some(1.0f),
        discoveryScore = 1.0f))
  }

  def makeSeedAttribution(userId: Id[User]) = {
    SeedAttribution(
      user = Some(makeUserAttribution(userId)),
      topic = Some(makeTopicAttribution()),
      keep = Some(makeKeepAttribution())
    )
  }

  def makeUserAttribution(userId: Id[User]) = {
    UserAttribution(
      friends = Seq.empty,
      others = 5
    )
  }

  def makeTopicAttribution() = TopicAttribution(topicName = "Testing")

  def makeKeepAttribution() = KeepAttribution(keeps = Seq.empty)

  def makeCompleteUriRecommendation(uriId: Int, userId: Int, masterScore: Float, url: String, wc: Int = 250,
    summaryImageWidth: Option[Int] = Some(700), summaryImageHeight: Option[Int] = Some(500), allScores: UriScores = defaultAllScores) = {
    val normalizedUri = makeNormalizedUri(uriId, url)
    val uriRecommendation = makeUriRecommendation(uriId, userId, masterScore, allScores)
    val uriSummary = URISummary(
      title = Some("The Personalized Web is Transforming our Relationship with Tech"),
      description = Some("Lorem ipsum dolor sit amet, consectetur adipiscing elit. Nunc nec augue a erat interdum varius." +
        "Nam faucibus euismod lorem in interdum. Donec ut enim vitae nibh mattis ultrices. Sed fermentum tellus eget odio " +
        "dapibus volutpat. Sed elementum sollicitudin metus, fringilla lacinia tortor fringilla vel. Mauris hendrerit " +
        "interdum neque eu vulputate. Nulla fermentum metus felis. In id velit dictum ligula iaculis pulvinar id sit " +
        "amet dolor. Proin eu augue id lectus viverra consectetur at sed orci. Suspendisse potenti."),
      wordCount = Some(wc),
      imageUrl = Some("https://djty7jcqog9qu.cloudfront.net/screenshot/f5d6aedb-fea9-485f-aead-f2a8d1f31ac5/1000x560.jpg"),
      imageWidth = summaryImageWidth,
      imageHeight = summaryImageHeight
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
