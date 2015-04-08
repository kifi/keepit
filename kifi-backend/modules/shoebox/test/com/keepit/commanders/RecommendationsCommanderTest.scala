package com.keepit.commanders

import com.google.inject.{ Provides, Singleton }
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.model._
import com.keepit.curator.{ FakeCuratorServiceClientModule, FakeCuratorServiceClientImpl, CuratorServiceClient }
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model._
import com.keepit.scraper.{ FakeScraperServiceClientModule, FakeScrapeSchedulerModule }
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
import org.specs2.mutable.Specification

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
    private val info = LimitedAugmentationInfo(keepers = Seq(Id[User](1)),
      keepersOmitted = 0,
      keepersTotal = 1,
      libraries = Seq((Id[Library](1), Id[User](1))),
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
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHeimdalServiceClientModule(),
    FakeMailModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeUserActionsModule(),
    FakeActorSystemModule()
  )

  val modules2 = modules.drop(2) ++ Seq(TestModule())

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

    "top recos" should {
      "work" in {
        withDb(modules2: _*) { implicit injector =>

          val uriRepo = inject[NormalizedURIRepo]

          db.readWrite { implicit s =>
            library().withUser(Id[User](1)).withName("scala").withSlug("scala").published().saved
            uriRepo.save(NormalizedURI(title = Some("scala 101"), url = "http://scala101.org", urlHash = UrlHash("xyz"), state = NormalizedURIStates.SCRAPED, externalId = ExternalId[NormalizedURI]("367f1d08-9dfe-43cb-8d60-d39482b00fb7")))
            user().withName("Martin", "Odersky").withUsername("Martin Odersky").withPictureName("cool").withId(ExternalId[User]("786171c5-f0db-4221-b655-e6aeb9a848f6")).saved
          }

          val commander = inject[RecommendationsCommander]
          val resF = commander.topRecos(Id[User](2), source = RecommendationSource.Site, subSource = RecommendationSubSource.RecommendationsFeed, more = false, recencyWeight = 0.7f, context = None)
          val res = Await.result(resF, Duration(5, "seconds"))
          val reco = res.recos.head
          print(reco)
          val js = Json.stringify(Json.toJson(reco))
          val expected = """{"kind":"keep","itemInfo":{"id":"367f1d08-9dfe-43cb-8d60-d39482b00fb7","title":"scala 101","url":"http://scala101.org","keepers":[{"id":"786171c5-f0db-4221-b655-e6aeb9a848f6","firstName":"Martin","lastName":"Odersky","pictureName":"cool.jpg","username":"Martin Odersky"}],"libraries":[{"owner":{"id":"786171c5-f0db-4221-b655-e6aeb9a848f6","firstName":"Martin","lastName":"Odersky","pictureName":"cool.jpg","username":"Martin Odersky"},"id":"l7jlKlnA36Su","name":"scala","path":"/Martin Odersky/scala"}],"others":0,"siteName":"scala101.org","summary":{}},"explain":"fake explain"}"""
          js === expected
        }
      }
    }
  }

}
