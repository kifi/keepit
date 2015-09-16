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
    def getFakeSearch(): SearchServiceClient = fakeSearch
  }

  val modules = Seq(
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

  def setupUsersAndLibrariesAndKeeps()(implicit injector: Injector) = {
    val emailIron = EmailAddress("tony@stark.com")
    val emailCaptain = EmailAddress("steve.rogers@hotmail.com")

    val (userIron, userCaptain) = db.readWrite { implicit s =>
      val userIron = user().withUsername("ironman").saved
      val userCaptain = user().withUsername("captainamerica").saved

      userEmailAddressCommander.intern(userId = userIron.id.get, address = emailIron).get
      userEmailAddressCommander.intern(userId = userCaptain.id.get, address = emailCaptain).get

      (userIron, userCaptain)
    }
    db.readOnlyMaster { implicit s =>
      userRepo.count === 2
    }
    val (libMurica, libScience, libIron) = db.readWrite { implicit s =>
      val libMurica = library().withOwner(userCaptain).withName("MURICA").withSlug("murica").published().saved
      val libScience = library().withOwner(userCaptain).withName("Science & Stuff").withSlug("science").published().saved
      val libIron = library().withOwner(userIron).withName("Iron Working").withSlug("iron").published().saved
      (libMurica, libScience, libIron)
    }
    db.readOnlyMaster { implicit s =>
      val allLibs = libraryRepo.all
      allLibs.length === 3
      allLibs.map(_.name) === Seq("MURICA", "Science & Stuff", "Iron Working")
      allLibs.map(_.slug.value) === Seq("murica", "science", "iron")
      allLibs.map(_.description) === Seq(None, None, None)
      allLibs.map(_.visibility) === Seq(LibraryVisibility.PUBLISHED, LibraryVisibility.PUBLISHED, LibraryVisibility.PUBLISHED)
      libraryMembershipRepo.count === 3
    }

    val t0 = DateTime.now().minusDays(10)
    db.readWrite { implicit s =>
      libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userIron.id.get), access = LibraryAccess.READ_ONLY, createdAt = t0))
      libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, inviterId = userCaptain.id.get, userId = Some(userIron.id.get), access = LibraryAccess.READ_ONLY, createdAt = t0))
    }
    db.readOnlyMaster { implicit s =>
      libraryInviteRepo.count === 2
    }

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
      libraryMembershipRepo.count === 5
      libraryRepo.get(libMurica.id.get).memberCount === 2
      libraryRepo.get(libScience.id.get).memberCount === 2
      libraryRepo.get(libIron.id.get).memberCount === 1
    }

    val t1 = DateTime.now().minusHours(6)
    val muricaSites = Seq("http://www.reddit.com/r/murica", "http://www.reddit.com/r/pics", "http://www.reddit.com/r/aww", "http://www.reddit.com/r/funny", "http://www.reddit.com/r/jokes", "http://www.reddit.com/r/news")
    val scienceSites = Seq("http://www.reddit.com/r/science")
    val ironSites = Seq("http://www.reddit.com/r/metalworking")

    db.readWrite { implicit s =>
      val muricaUris = for (site <- muricaSites) yield uriRepo.save(NormalizedURI.withHash(site, Some("Reddit")))
      val muricaUrls = for (uri <- muricaUris) yield urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))
      val muricaKeeps = KeepFactory.keeps(muricaSites.length).zipWithIndex.map {
        case (keep, i) =>
          keep.withTitle("Reddit").withUser(userCaptain).withUri(muricaUris(i)).withKeptAt(t1.plusMinutes(i)).withLibrary(libMurica)
      } saved

      // The Science keeps are all newer than the Murica keeps (see keptAt = t1...)
      val scienceUris = for (site <- scienceSites) yield uriRepo.save(NormalizedURI.withHash(site, Some("Reddit")))
      val scienceUrls = for (uri <- scienceUris) yield urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))
      val scienceKeeps = KeepFactory.keeps(scienceSites.length).zipWithIndex.map {
        case (keep, i) =>
          keep.withTitle("Reddit").withUser(userCaptain).withUri(scienceUris(i)).withKeptAt(t1.plusMinutes(muricaSites.length + i)).withLibrary(libScience)
      } saved

      val ironUris = for (site <- ironSites) yield uriRepo.save(NormalizedURI.withHash(site, Some("Reddit")))
      val ironUrls = for (uri <- ironUris) yield urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))
      val ironKeeps = KeepFactory.keeps(ironSites.length).zipWithIndex.map {
        case (keep, i) =>
          keep.withTitle("Reddit").withUser(userIron).withUri(ironUris(i)).withKeptAt(t1.plusMinutes(i)).withLibrary(libIron)
      } saved
    }

    db.readOnlyMaster { implicit s =>
      keepRepo.count === muricaSites.length + scienceSites.length + ironSites.length
    }
    (userIron, userCaptain, libMurica, libScience, libIron)
  }

  "RecommendationsCommanderTest" should {
    implicit val config = PublicIdConfiguration("secret key")
    "maybeUpdatesFromFollowedLibraries" should {
      "sample a simple sequence fairly" in {
        withInjector(modules: _*) { implicit injector =>
          val recoCommander = inject[RecommendationsCommander]
          val seqOfSeqs = Seq(
            Seq(11, 12, 13, 14, 15, 16),
            Seq(21, 22, 23),
            Seq(31),
            Seq(41, 42, 43, 44)
          )
          recoCommander.sampleFairly(seqOfSeqs, maxPerSeq = 3) === Seq(11, 12, 13, 21, 22, 23, 31, 41, 42, 43)
        }
      }

      "sample actual keeps fairly" in {
        withDb(modules: _*) { implicit injector =>
          val t1 = DateTime.now()
          implicit val config = inject[PublicIdConfiguration]
          val recoCommander = inject[RecommendationsCommander]
          val (userIron, userCaptain, libMurica, libScience, libIron) = setupUsersAndLibrariesAndKeeps()

          db.readOnlyMaster { implicit s =>
            val resF = recoCommander.maybeUpdatesFromFollowedLibraries(userIron.id.get, 20, maxUpdatesPerLibrary = 5)
            val Some(recos) = Await.result(resF, Duration(5, "seconds"))
            recos.itemInfo.length === 6
            recos.itemInfo.map(_.url) ===
              Seq("http://www.reddit.com/r/science",
                "http://www.reddit.com/r/news",
                "http://www.reddit.com/r/jokes",
                "http://www.reddit.com/r/funny",
                "http://www.reddit.com/r/aww",
                "http://www.reddit.com/r/pics")
          }
        }
      }
    }
  }

}
