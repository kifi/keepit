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
import com.keepit.model.UserFactory
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._

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

  def externalIdForTitle(title: String)(implicit injector: Injector): String = forTitle(title).externalId.id

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

  def libraryCard(libraryId: Id[Library])(implicit injector: Injector): LibraryCardInfo = {
    val viewerOpt = inject[FakeUserActionsHelper].fixedUser.flatMap(_.id)
    db.readOnlyMaster { implicit session =>
      val library = libraryRepo.get(libraryId)
      val owner = basicUserRepo.load(library.ownerId)
      inject[LibraryCardCommander].createLibraryCardInfo(library, owner, viewerOpt, withFollowing = true, ProcessedImageSize.Medium.idealSize)
    }
  }

  "KeepsController" should {

    "update keep note" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, keep1, lib1) = db.readWrite { implicit s =>
          val user = UserFactory.user().withUsername("spiderman").saved
          val lib = library().withOwner(user).saved
          val keep = KeepFactory.keep().withUser(user).withLibrary(lib).saved
          (user, keep, lib)
        }
        implicit val publicIdConfig = inject[PublicIdConfiguration]
        val keepsController = inject[KeepsController]
        val pubId1 = Keep.publicId(keep1.id.get)
        val testPath = com.keepit.controllers.website.routes.KeepsController.editKeepNote(pubId1).url
        inject[FakeUserActionsHelper].setUser(user1)

        // test adding a note (without hashtags)
        val result1 = keepsController.editKeepNote(pubId1)(FakeRequest("POST", testPath).withBody(Json.obj("note" -> "thwip!")))
        status(result1) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val keep = keepRepo.getOpt(keep1.externalId).get
          keep.note === Some("thwip!")
          tagCommander.getTagsForKeep(keep.id.get) === Seq()
        }

        // test removing a note
        val result2 = keepsController.editKeepNote(pubId1)(FakeRequest("POST", testPath).withBody(Json.obj("note" -> "")))
        status(result2) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val keep = keepRepo.getOpt(keep1.externalId).get
          keep.note === None
          tagCommander.getTagsForKeep(keep.id.get) === Seq()
        }

        // test adding a note (with hashtags)
        val result3 = keepsController.editKeepNote(pubId1)(FakeRequest("POST", testPath).withBody(Json.obj("note" -> "thwip! #spiders [#avengers] [#tonysucks] blah")))
        status(result3) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val keep = keepRepo.getOpt(keep1.externalId).get
          keep.note === Some("thwip! #spiders [#avengers] [#tonysucks] blah")
          tagCommander.getTagsForKeep(keep.id.get).map(_.tag).toSet === Set("tonysucks", "avengers")
        }

      }

    }
  }
}
