package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders._
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.helprank.HelpRankTestHelper
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.{ S3ImageStore, S3ImageConfig, FakeShoeboxStoreModule }
import com.keepit.common.time._
import com.keepit.common.util.{ AuthorElement, TextElement, LibraryElement }
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.{ KeepToCollection, UserFactory, _ }
import com.keepit.search.{ FakeSearchServiceClientModule, _ }
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.{ BasicAuthor, BasicUser }
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.json._
import play.api.mvc.Result
import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.Future

class MobileKeepsControllerTest extends Specification with ShoeboxTestInjector with HelpRankTestHelper {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  import com.keepit.commanders.RawBookmarkRepresentation._

  implicit val helperFormat = RawBookmarkRepresentation.helperFormat

  implicit def pubIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  implicit def s3Config(implicit injector: Injector): S3ImageConfig = inject[S3ImageConfig]

  // NOTE: No attemp to write the trait SourceAttribution
  implicit val rawBookmarkRepwrites = new Writes[RawBookmarkRepresentation] {
    def writes(keep: RawBookmarkRepresentation): JsValue = {
      val tmp = RawBookmarkRepresentationWithoutAttribution(keep.title, keep.url, keep.canonical, keep.openGraph, keep.keptAt, keep.note)
      Json.toJson(tmp)
    }
  }

  def prenormalize(url: String)(implicit injector: Injector): String = normalizationService.prenormalize(url).get

  def libraryCard(libraryId: Id[Library])(implicit injector: Injector): LibraryCardInfo = {
    val viewerOpt = inject[FakeUserActionsHelper].fixedUser.flatMap(_.id)
    db.readOnlyMaster { implicit session =>
      val library = libraryRepo.get(libraryId)
      val owner = basicUserRepo.load(library.ownerId)
      inject[LibraryCardCommander].createLibraryCardInfo(library, owner, viewerOpt, withFollowing = true, ProcessedImageSize.Medium.idealSize)
    }
  }
  def toSimpleKeepMembers(keep: Keep, owner: BasicUser, library: LibraryCardInfo): KeepMembers = {
    import KeepMember._
    KeepMembers(Seq(Library(library, keep.keptAt, Some(owner))), Seq(User(owner, keep.keptAt, Some(owner))), Seq.empty)
  }

  "MobileKeepsController" should {

    "allCollections" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, collections) = db.readWrite { implicit session =>
          val user = UserFactory.user().withName("Eishay", "Smith").withUsername("test").saved

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user.id.get, name = Hashtag("myCollaction1"))) ::
            collectionRepo.save(Collection(userId = user.id.get, name = Hashtag("myCollaction2"))) ::
            collectionRepo.save(Collection(userId = user.id.get, name = Hashtag("myCollaction3"))) ::
            Nil

          val lib = LibraryFactory.library().saved
          collections.map { c =>
            val k = KeepFactory.keep().withLibrary(lib).saved
            inject[KeepToCollectionRepo].save(KeepToCollection(keepId = k.id.get, collectionId = c.id.get))
          }
          (user, collections)
        }

        val path = com.keepit.controllers.mobile.routes.MobileKeepsController.allCollections().url
        path === "/m/1/collections/all"

        inject[FakeUserActionsHelper].setUser(user)
        val request = FakeRequest("GET", path)
        val result = inject[MobileKeepsController].allCollections(sort = "name")(request)
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val (ext1, ext2, ext3) = db.readOnlyMaster { implicit session =>
          val collections = collectionRepo.all
          collections.length === 3
          (collections(0).externalId, collections(1).externalId, collections(2).externalId)
        }

        val expected = Json.parse(s"""
          {"keeps":0,
           "collections":[
               {"id":"${ext1}","name":"myCollaction1","keeps":1},
               {"id":"${ext2}","name":"myCollaction2","keeps":1},
               {"id":"${ext3}","name":"myCollaction3","keeps":1}
            ]}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "edit note v2" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, keep1, keepWithTags, keepInactive) = db.readWrite { implicit session =>
          val user = UserFactory.user().withName("Eishay", "Smith").withUsername("test").saved
          val lib = library().withOwner(user).saved
          val keep1 = KeepFactory.keep().withUser(user).withLibrary(lib).withTitle("default").saved
          val keep2 = KeepFactory.keep().withUser(user).withLibrary(lib).withTitle("default1").saved
          val keepInactive = KeepFactory.keep().withUser(user).withLibrary(lib).withState(KeepStates.INACTIVE).saved

          collectionRepo.count(user.id.get) === 0
          keepToCollectionRepo.count === 0

          (user, keep1, keep2, keepInactive)
        }

        def editKeepInfoV2(user: User, keep: Keep, body: JsObject): Future[Result] = {
          inject[FakeUserActionsHelper].setUser(user)
          val path = com.keepit.controllers.mobile.routes.MobileKeepsController.editKeepInfoV2(keep.externalId).url
          val request = FakeRequest("POST", path).withBody(body)
          inject[MobileKeepsController].editKeepInfoV2(keep.externalId)(request)
        }

        val testInactiveKeep = editKeepInfoV2(user, keepInactive, Json.obj("title" -> "blahablhablhahbla"))
        status(testInactiveKeep) must equalTo(NOT_FOUND)

        val testEditTitle = editKeepInfoV2(user, keep1, Json.obj("title" -> ""))
        status(testEditTitle) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === None
          currentKeep.note === None
        }

        val testEditNote = editKeepInfoV2(user, keep1, Json.obj("note" -> "first comment!"))
        status(testEditNote) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === None
          currentKeep.note === Some("first comment!")
        }

        val testEditBoth = editKeepInfoV2(user, keep1, Json.obj("title" -> "a real keep", "note" -> "a real note"))
        status(testEditBoth) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === Some("a real keep")
          currentKeep.note === Some("a real note")
        }

        val testEditNothing = editKeepInfoV2(user, keep1, Json.obj())
        status(testEditNothing) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === Some("a real keep")
          currentKeep.note === Some("a real note")
          keepToCollectionRepo.count === 0
        }

        val testEditWithHashtags = editKeepInfoV2(user, keep1, Json.obj("note" -> "a real [#note]"))
        status(testEditWithHashtags) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === Some("a real keep")
          currentKeep.note === Some("a real [#note]")
          collectionRepo.getHashtagsByKeepId(currentKeep.id.get).map(_.tag) === Set("note")
        }

        val testEditWithHashtags2 = editKeepInfoV2(user, keep1, Json.obj("note" -> "a real [#note]. #Finally! [#woo[hoo\\]]"))
        status(testEditWithHashtags2) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === Some("a real keep")
          currentKeep.note === Some("a real [#note]. #Finally! [#woo[hoo\\]]")
          collectionRepo.getHashtagsByKeepId(currentKeep.id.get).map(_.tag) === Set("note", "woo[hoo]")
        }
      }
    }
  }

}
