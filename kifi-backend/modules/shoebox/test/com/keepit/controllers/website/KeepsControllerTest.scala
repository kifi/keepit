package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders._
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db._
import com.keepit.common.db.slick.Database

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.helprank.HelpRankTestHelper
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.search._
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.json._
import play.api.test.Helpers._
import play.api.test._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

class KeepsControllerTest extends Specification with ShoeboxTestInjector with HelpRankTestHelper {

  val controllerTestModules = Seq(
    FakeUserActionsModule(),
    FakeHttpClientModule(),
    FakeGraphServiceModule(),
    FakeShoeboxServiceModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCortexServiceClientModule()
  )

  def deepTest(a: JsValue, b: JsValue, path: Option[String] = None): Option[String] = {
    (a.asOpt[JsObject], b.asOpt[JsObject]) match {
      case (Some(aObj), Some(bObj)) =>
        (aObj.keys ++ bObj.keys).flatMap(k => deepTest(aObj \ k, bObj \ k, path.map(_ + k))).headOption
      case _ =>
        (a.asOpt[JsArray], b.asOpt[JsArray]) match {
          case (Some(aArr), Some(bArr)) if aArr.value.length != bArr.value.length =>
            Some(s"${path.getOrElse("")}: lengths unequal")
          case (Some(aArr), Some(bArr)) =>
            (aArr.value zip bArr.value).flatMap { case (av, bv) => deepTest(av, bv, path.map(_ + "[i]")) }.headOption
          case _ if a != b =>
            println(s"Found discrepancy: $a != $b")
            Some(s"${path.getOrElse("")}: $a != $b")
          case _ => None
        }
    }
  }

  def externalIdForTitle(title: String)(implicit injector: Injector): String = forTitle(title).externalId.id
  def externalIdForCollection(userId: Id[User], name: String)(implicit injector: Injector): String = forCollection(userId, name).externalId.id

  def sourceForTitle(title: String)(implicit injector: Injector): KeepSource = forTitle(title).source

  def stateForTitle(title: String)(implicit injector: Injector): String = forTitle(title).state.value

  def forTitle(title: String)(implicit injector: Injector): Keep = {
    // hilariously inefficient, but it's only for testing
    inject[Database].readWrite { implicit session =>
      val keeps = inject[KeepRepo].all().filter(_.title == title)
      keeps.size === 1
      keeps.head
    }
  }

  def forCollection(userId: Id[User], name: String)(implicit injector: Injector): Collection = {
    inject[Database].readWrite { implicit session =>
      val collections = inject[CollectionRepo].getByUserAndName(userId, Hashtag(name))
      collections.size === 1
      collections.head
    }
  }

  def libraryCard(libraryId: Id[Library])(implicit injector: Injector): LibraryCardInfo = {
    val viewerOpt = inject[FakeUserActionsHelper].fixedUser.flatMap(_.id)
    db.readOnlyMaster { implicit session =>
      val library = libraryRepo.get(libraryId)
      val owner = basicUserRepo.load(library.ownerId)
      inject[LibraryCardCommander].createLibraryCardInfo(library, owner, viewerOpt, withFollowing = true, ProcessedImageSize.Medium.idealSize)
    }
  }

  "KeepsController" should {

    "search tags for user" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val t1 = new DateTime(2014, 9, 1, 21, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val user1 = db.readWrite { implicit session =>
          UserFactory.user().withName("Mega", "Tron").withUsername("test").saved
        }
        inject[LibraryCommander].internSystemGeneratedLibraries(user1.id.get)
        db.readWrite { implicit session =>
          val tagA = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("tagA"), createdAt = t1))
          val tagB = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("tagB"), createdAt = t1))
          val tagC = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("tagC"), createdAt = t1.plusMinutes(1)))
          val tagD = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("tagD"), createdAt = t1.plusMinutes(2)))

          uriRepo.count === 0
          val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val mainLib = libraryRepo.getBySpaceAndSlug(LibrarySpace.fromUserId(user1.id.get), LibrarySlug("main"))
          val keep1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url,
            uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1, keptAt = t1, state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(mainLib.get.id.get)))
          val keep2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url,
            uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1, keptAt = t1, state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(mainLib.get.id.get)))

          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = tagA.id.get, createdAt = t1.plusMinutes(1)))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep2.id.get, collectionId = tagA.id.get, createdAt = t1.plusMinutes(3)))
          collectionRepo.save(tagA.copy(lastKeptTo = Some(t1.plusMinutes(3))))

          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = tagC.id.get, createdAt = t1.plusMinutes(4)))
          collectionRepo.save(tagC.copy(lastKeptTo = Some(t1.plusMinutes(4))))

          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = tagD.id.get, createdAt = t1.plusMinutes(6)))
          collectionRepo.save(tagD.copy(lastKeptTo = Some(t1.plusMinutes(6))))

          (user1)
        }

        inject[FakeUserActionsHelper].setUser(user1)
        val request1 = FakeRequest("GET", com.keepit.controllers.website.routes.KeepsController.searchUserTags("").url)
        val result1 = inject[KeepsController].searchUserTags("ta")(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val expected1 = Json.parse(
          s"""
           |{ "results":
              |[
              |  { "tag":"tagA","keepCount":2,"matches":[[0,2]] },
              |  { "tag":"tagC","keepCount":1,"matches":[[0,2]] },
              |  { "tag":"tagD","keepCount":1,"matches":[[0,2]] }
              |]
           | }
           """.stripMargin)
        Json.parse(contentAsString(result1)) must equalTo(expected1)
      }
    }
  }
}
