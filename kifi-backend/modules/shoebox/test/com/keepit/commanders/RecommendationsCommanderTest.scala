package com.keepit.commanders

import com.google.inject.{ Injector, Provides, Singleton }
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.model._
import com.keepit.curator.{ FakeCuratorServiceClientModule, FakeCuratorServiceClientImpl, CuratorServiceClient }
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model._
import com.keepit.search.augmentation._
import com.keepit.search.{ SearchServiceClient, FakeSearchServiceClient, FakeSearchServiceClientModule }
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import com.keepit.model.KeepFactory._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import net.codingwell.scalaguice.ScalaModule
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration.Duration
import play.api.libs.json._

class RecommendationsCommanderTest extends Specification with ShoeboxTestInjector {

  val fakeCurator = new FakeCuratorServiceClientImpl(null) {
    private val reco = RecoInfo(userId = None, uriId = Id[NormalizedURI](1), score = 3.14f, explain = Some("fake explain"), attribution = None)
    override def topRecos(userId: Id[User], source: RecommendationSource, subSource: RecommendationSubSource, more: Boolean, recencyWeight: Float, context: Option[String]): Future[URIRecoResults] =
      Future.successful {
        val recos = Seq(reco)
        URIRecoResults(recos, "")
      }
  }

  val fakeSearch = new FakeSearchServiceClient() {
    val now = currentDateTime
    private val info = LimitedAugmentationInfo(
      None,
      keepers = Seq(Id[User](1) -> now),
      keepersOmitted = 0,
      keepersTotal = 1,
      libraries = Seq((Id[Library](1), Id[User](1), now)),
      librariesOmitted = 0,
      librariesTotal = 1,
      tags = Seq(),
      tagsOmitted = 0)
    override def augment(userId: Option[Id[User]], showPublishedLibraries: Boolean, maxKeepersShown: Int, maxLibrariesShown: Int, maxTagsShown: Int, items: Seq[AugmentableItem]): Future[Seq[LimitedAugmentationInfo]] = {
      Future.successful(Seq(info))
    }
  }

  case class TestModule() extends ScalaModule {
    def configure() {}

    @Singleton
    @Provides
    def getFakeCurator(): CuratorServiceClient = fakeCurator

    @Singleton
    @Provides
    def getFakeSearch(): SearchServiceClient = fakeSearch
  }

  val modules = Seq(
    FakeCuratorServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeShoeboxServiceModule(),
    FakeShoeboxStoreModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHeimdalServiceClientModule(),
    FakeMailModule(),
    FakeCortexServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeUserActionsModule(),
    FakeActorSystemModule()
  )

  val modules2 = modules.drop(2) ++ Seq(TestModule())

  def setupUsers()(implicit injector: Injector) = {
    val t1 = DateTime.now()
    val emailRepo = inject[UserEmailAddressRepo]
    val emailIron = EmailAddress("tony@stark.com")
    val emailCaptain = EmailAddress("steve.rogers@hotmail.com")

    val (userIron, userCaptain) = db.readWrite { implicit s =>
      val userIron = user().withUsername("ironman").saved
      val userCaptain = user().withUsername("captainamerica").saved

      emailRepo.save(UserEmailAddress(userId = userIron.id.get, address = emailIron))
      emailRepo.save(UserEmailAddress(userId = userCaptain.id.get, address = emailCaptain))

      (userIron, userCaptain)
    }
    db.readOnlyMaster { implicit s =>
      userRepo.count === 2
    }
    (userIron, userCaptain)
  }

  def setupLibraries()(implicit injector: Injector) = {
    val (userIron, userCaptain) = setupUsers
    val (libMurica, libScience) = db.readWrite { implicit s =>
      val libMurica = library().withUser(userCaptain).withName("MURICA").withSlug("murica").published().saved
      val libScience = library().withUser(userCaptain).withName("Science & Stuff").withSlug("science").published().saved
      (libMurica, libScience)
    }
    db.readOnlyMaster { implicit s =>
      val allLibs = libraryRepo.all
      allLibs.length === 2
      allLibs.map(_.name) === Seq("MURICA", "Science & Stuff")
      allLibs.map(_.slug.value) === Seq("murica", "science")
      allLibs.map(_.description) === Seq(None, None)
      allLibs.map(_.visibility) === Seq(LibraryVisibility.PUBLISHED, LibraryVisibility.PUBLISHED)
      libraryMembershipRepo.count === 2
    }
    (userIron, userCaptain, libMurica, libScience)
  }

  def setupInvites()(implicit injector: Injector) = {
    val (userIron, userCaptain, libMurica, libScience) = setupLibraries

    val t1 = DateTime.now().minusDays(10)
    db.readWrite { implicit s =>
      libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userIron.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))
      libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, inviterId = userCaptain.id.get, userId = Some(userIron.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))

      (userIron, userCaptain, libMurica, libScience)
    }
    db.readOnlyMaster { implicit s =>
      libraryInviteRepo.count === 2
    }
    (userIron, userCaptain, libMurica, libScience)
  }

  def setupAcceptedInvites()(implicit injector: Injector) = {
    val (userIron, userCaptain, libMurica, libScience) = setupInvites
    db.readWrite { implicit s =>
      val inv1 = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId = libMurica.id.get, userId = userIron.id.get).head
      val inv2 = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId = libScience.id.get, userId = userIron.id.get).head
      libraryInviteRepo.save(inv1.withState(LibraryInviteStates.ACCEPTED))
      libraryInviteRepo.save(inv2.withState(LibraryInviteStates.ACCEPTED))

      membership().fromLibraryInvite(inv1).saved
      membership().fromLibraryInvite(inv2).saved

      libraryRepo.save(libMurica.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libMurica.id.get)))
      libraryRepo.save(libScience.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libScience.id.get)))
    }
    db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.count === 4
      libraryRepo.get(libMurica.id.get).memberCount === 2
      libraryRepo.get(libScience.id.get).memberCount === 2
    }
    (userIron, userCaptain, libMurica, libScience)
  }

  def setupKeeps()(implicit injector: Injector) = {
    val (userIron, userCaptain, libMurica, libScience) = setupAcceptedInvites
    val t1 = DateTime.now().minusHours(6)
    val site1 = "http://www.reddit.com/r/murica"
    val site2 = "http://www.freedom.org/"
    val site3 = "http://www.mcdonalds.com/"

    db.readWrite { implicit s =>
      val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("Reddit")))
      val uri2 = uriRepo.save(NormalizedURI.withHash(site2, Some("Freedom")))
      val uri3 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds")))

      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
      val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))

      // Murica keeps
      val keep1 = keepRepo.save(Keep(title = Some("Reddit"), userId = userCaptain.id.get, url = url1.url, urlId = url1.id.get,
        uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1, keptAt = t1,
        visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint))
      val keep2 = keepRepo.save(Keep(title = Some("Freedom"), userId = userCaptain.id.get, url = url2.url, urlId = url2.id.get,
        uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1, keptAt = t1,
        visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint))

      // Science keeps
      val keep3 = keepRepo.save(Keep(title = Some("McDonalds"), userId = userCaptain.id.get, url = url3.url, urlId = url3.id.get,
        uriId = uri3.id.get, source = KeepSource.keeper, createdAt = t1, keptAt = t1,
        visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libScience.id.get), inDisjointLib = libScience.isDisjoint))

      val tag1 = collectionRepo.save(Collection(userId = userCaptain.id.get, name = Hashtag("USA")))
      val tag2 = collectionRepo.save(Collection(userId = userCaptain.id.get, name = Hashtag("food")))

      keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = tag1.id.get))
      keepToCollectionRepo.save(KeepToCollection(keepId = keep2.id.get, collectionId = tag1.id.get))
      keepToCollectionRepo.save(KeepToCollection(keepId = keep3.id.get, collectionId = tag1.id.get))
      keepToCollectionRepo.save(KeepToCollection(keepId = keep3.id.get, collectionId = tag2.id.get))
    }
    db.readOnlyMaster { implicit s =>
      keepRepo.count === 3
      collectionRepo.count(userCaptain.id.get) === 2
      keepToCollectionRepo.count === 4
    }
    (userIron, userCaptain, libMurica, libScience)
  }

  "RecommendationsCommanderTest" should {
    implicit val config = PublicIdConfiguration("secret key")
    "topPublicLibraryRecos" should {
      "work" in {
        withDb(modules: _*) { implicit injector =>
          val commander = inject[RecommendationsCommander]
          val curator = inject[CuratorServiceClient].asInstanceOf[FakeCuratorServiceClientImpl]
          val (user1, lib1, lib2, lib3) = db.readWrite { implicit rw =>
            val owner = user().saved
            (
              user().saved,
              library().withUser(owner).withName("Scala").published().saved,
              library().withUser(owner).withName("Secret").saved, // secret - shouldn't be a reco
              library().withUser(owner).withName("Java").published().saved
            )
          }
          db.readWrite { implicit rw =>
            keep().withLibrary(lib1).saved
            keep().withLibrary(lib2).saved
            keep().withLibrary(lib2).saved
          }

          curator.topLibraryRecosExpectations(user1.id.get) = Seq(
            LibraryRecoInfo(user1.id.get, lib3.id.get, 7, ""),
            LibraryRecoInfo(user1.id.get, lib2.id.get, 8, ""),
            LibraryRecoInfo(user1.id.get, lib1.id.get, 8, "")
          )

          val recosF = commander.topPublicLibraryRecos(user1.id.get, 5, RecommendationSource.Site, RecommendationSubSource.RecommendationsFeed, context = None)
          val recos = Await.result(recosF.map { _.recos }, Duration(5, "seconds")).map(_._2)
          recos.size === 2
          recos(0).itemInfo.name === "Java"
          recos(1).itemInfo.name === "Scala"
        }
      }
    }

    "maybeUpdatesFromFollowedLibraries" should {
      "work" in {
        withDb(modules: _*) { implicit injector =>
          val t1 = DateTime.now()
          implicit val config = inject[PublicIdConfiguration]
          val recoCommander = inject[RecommendationsCommander]
          val (userIron, userCaptain, libMurica, libScience) = setupKeeps

          db.readOnlyMaster { implicit s =>
            val resF = recoCommander.maybeUpdatesFromFollowedLibraries(userIron.id.get)
            val Some(recos) = Await.result(resF, Duration(5, "seconds"))
            recos.itemInfo.length === 3
          }
        }
      }
    }

    "top recos" should {
      "work" in {
        withDb(modules2: _*) { implicit injector =>

          val uriRepo = inject[NormalizedURIRepo]

          db.readWrite { implicit s =>
            library().withUser(Id[User](1)).withName("scala").withSlug("scala").published().withColor(LibraryColor.BLUE).saved
            uriRepo.save(NormalizedURI(title = Some("scala 101"), url = "http://scala101.org", urlHash = UrlHash("xyz"), state = NormalizedURIStates.SCRAPED, externalId = ExternalId[NormalizedURI]("367f1d08-9dfe-43cb-8d60-d39482b00fb7")))
            user().withName("Martin", "Odersky").withUsername("Martin Odersky").withPictureName("cool").withId(ExternalId[User]("786171c5-f0db-4221-b655-e6aeb9a848f6")).saved
          }

          val commander = inject[RecommendationsCommander]
          val resF = commander.topRecos(Id[User](2), source = RecommendationSource.Site, subSource = RecommendationSubSource.RecommendationsFeed, more = false, recencyWeight = 0.7f, context = None)
          val res = Await.result(resF, Duration(5, "seconds"))
          val reco = res.recos.head
          print(reco)
          val js = Json.toJson(reco)
          val expected =
            """
              |{
              |  "kind": "keep",
              |  "itemInfo": {
              |    "id": "367f1d08-9dfe-43cb-8d60-d39482b00fb7",
              |    "title": "scala 101",
              |    "url": "http://scala101.org",
              |    "keepers": [
              |      {
              |        "id": "786171c5-f0db-4221-b655-e6aeb9a848f6",
              |        "firstName": "Martin",
              |        "lastName": "Odersky",
              |        "pictureName": "cool.jpg",
              |        "username": "Martin Odersky"
              |      }
              |    ],
              |    "libraries": [
              |      {
              |        "owner": {
              |          "id": "786171c5-f0db-4221-b655-e6aeb9a848f6",
              |          "firstName": "Martin",
              |          "lastName": "Odersky",
              |          "pictureName": "cool.jpg",
              |          "username": "Martin Odersky"
              |        },
              |        "id": "l7jlKlnA36Su",
              |        "name": "scala",
              |        "path": "/Martin Odersky/scala",
              |        "color": "#447ab7"
              |      }
              |    ],
              |    "others": 0,
              |    "siteName": "scala101.org",
              |    "summary": {
              |
              |    }
              |  },
              |  "explain": "fake explain"
              |}
            """.stripMargin
          js === Json.parse(expected)
        }
      }
    }
  }

}
