package com.keepit.curator

import java.util.concurrent.atomic.AtomicInteger

import com.google.inject.{ Module, Injector }
import com.keepit.common.db.{ SequenceNumber, Id }
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

  def makeKeeps(userId: Id[User], howMany: Int, shoebox: FakeShoeboxServiceClientImpl, startId: Int = 1): Seq[Keep] = {
    (startId to startId + howMany - 1).flatMap { i =>
      shoebox.saveBookmarks(Keep(
        uriId = Id[NormalizedURI](i),
        urlId = Id[URL](i),
        url = "https://kifi.com",
        userId = userId,
        state = KeepStates.ACTIVE,
        source = KeepSource.keeper,
        visibility = LibraryVisibility.DISCOVERABLE,
        libraryId = Some(Id[Library](1)),
        inDisjointLib = true))
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
        libraryId = Some(Id[Library](1)),
        inDisjointLib = true))
    }
  }

  def makeUser(num: Int, shoebox: FakeShoeboxServiceClientImpl) =
    shoebox.saveUsers(User(
      id = Some(Id[User](num)),
      firstName = "Some",
      lastName = "User" + num, username = Username("test"), normalizedUsername = "test",
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
    discoveryScore = 1.0f,
    libraryInducedScore = Some(0f))

  def makeUriRecommendation(uriId: Int, userIdInt: Int, masterScore: Float, allScores: UriScores = defaultAllScores) = {
    val userId = Id[User](userIdInt)
    UriRecommendation(
      uriId = Id[NormalizedURI](uriId),
      userId = userId,
      masterScore = masterScore,
      state = UriRecommendationStates.ACTIVE,
      allScores = allScores,
      viewed = 0, clicked = 0, kept = false,
      attribution = makeSeedAttribution(userId),
      topic1 = None,
      topic2 = None)
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
        discoveryScore = 1.0f,
        libraryInducedScore = Some(0f)),
      viewed = 0, clicked = 0, kept = false,
      attribution = makeSeedAttribution(userId),
      topic1 = None,
      topic2 = None)
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
      others = 5,
      None
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
    shoebox.saveURISummary(savedUri.id.get, uriSumm)
    (savedUri, savedUriReco, uriSumm)
  }

  def makeSeedItems(userId: Id[User]): Seq[SeedItem] = {
    val seedItem1 = SeedItem(userId = userId, uriId = Id[NormalizedURI](1), url = "url1", seq = SequenceNumber[SeedItem](1), priorScore = None, timesKept = 1000, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.TooMany, discoverable = true)
    val seedItem2 = SeedItem(userId = userId, uriId = Id[NormalizedURI](2), url = "url2", seq = SequenceNumber[SeedItem](2), priorScore = None, timesKept = 10, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](3))), discoverable = true)
    val seedItem3 = SeedItem(userId = userId, uriId = Id[NormalizedURI](3), url = "url3", seq = SequenceNumber[SeedItem](3), priorScore = None, timesKept = 93, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](2))), discoverable = true)
    val seedItem4 = SeedItem(userId = userId, uriId = Id[NormalizedURI](4), url = "url4", seq = SequenceNumber[SeedItem](4), priorScore = None, timesKept = 20, lastSeen = currentDateTime, lastKept = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    seedItem1 :: seedItem2 :: seedItem3 :: seedItem4 :: Nil
  }

  val libraryNameSuffix = new AtomicInteger(1)

  def saveLibraryInfo(libraryId: Int, ownerId: Int, keepCount: Int = 10, memberCount: Int = 9, name: Option[String] = None)(implicit rw: RWSession, injector: Injector): CuratorLibraryInfo = {
    val libInfo = CuratorLibraryInfo(libraryId = Id[Library](libraryId), ownerId = Id[User](ownerId), state = CuratorLibraryInfoStates.ACTIVE,
      keepCount = keepCount, memberCount = memberCount, visibility = LibraryVisibility.PUBLISHED, lastKept = Some(currentDateTime.minusDays(5)), lastFollowed = None,
      kind = LibraryKind.USER_CREATED, libraryLastUpdated = currentDateTime, name = name.getOrElse("Library Name " + libraryNameSuffix.getAndIncrement.toString),
      descriptionLength = 30)
    inject[CuratorLibraryInfoRepo].save(libInfo)
  }

  def makeLibraryRecommendation(libraryId: Int, userId: Int, masterScore: Float) = {
    LibraryRecommendation(
      libraryId = Id[Library](libraryId),
      userId = Id[User](userId),
      masterScore = masterScore,
      allScores = LibraryScores(
        socialScore = 1,
        interestScore = 1,
        recencyScore = 1,
        popularityScore = 1,
        sizeScore = 1,
        contentScore = None
      )
    )
  }

  def saveLibraryMembership(userId: Id[User], libId: Id[Library], owner: Boolean = false)(implicit rw: RWSession, injector: Injector): CuratorLibraryMembershipInfo = {
    inject[CuratorLibraryMembershipInfoRepo].save(CuratorLibraryMembershipInfo(
      userId = userId,
      libraryId = libId,
      access = if (owner) LibraryAccess.OWNER else LibraryAccess.READ_ONLY,
      state = CuratorLibraryMembershipInfoStates.ACTIVE
    ))
  }
}
