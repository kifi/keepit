package com.keepit.controllers.ext

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule

import com.keepit.common.controller.{ FakeUserActionsHelper }
import com.keepit.common.crypto.{ FakeCryptoModule, PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ FakeSocialGraphModule, BasicUserRepo }
import com.keepit.common.store.ImagePath
import com.keepit.common.time._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model._
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.social.BasicUser
import com.keepit.test.{ DbInjectionHelper, ShoeboxTestInjector }

import org.joda.time.DateTime
import org.specs2.mutable.Specification

import play.api.libs.json.{ Json, JsObject, JsNull }
import play.api.mvc.{ Call, Result }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class ExtLibraryControllerTest extends Specification with ShoeboxTestInjector with DbInjectionHelper {

  val controllerTestModules = Seq(
    FakeCryptoModule(),
    FakeShoeboxServiceModule(),
    FakeKeepImportsModule(),
    FakeSliderHistoryTrackerModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeHttpClientModule()
  )

  args(skipAll = true)

  "ExtLibraryController" should {
    "format a library properly" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, lib) = db.readWrite { implicit s =>
          val user = UserFactory.user().withName("Morgan", "Freeman").withUsername("morgan").saved
          val lib = LibraryFactory.library().withName("Million Dollar Baby").withOwner(user).withVisibility(LibraryVisibility.PUBLISHED).withSlug("baby").withColor(LibraryColor.RED).saved
          (user, lib)
        }
        implicit val config = inject[PublicIdConfiguration]
        val pubId = Library.publicId(lib.id.get).id
        val basicUserRepo = inject[BasicUserRepo]
        val result = getLibraries(user)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        db.readOnlyMaster { implicit session =>
          val payload = Json.parse(contentAsString(result))
          val libraryJson = (payload \ "libraries").as[Seq[JsObject]].head
          libraryJson === Json.obj(
            "id" -> pubId,
            "name" -> "Million Dollar Baby",
            "color" -> LibraryColor.RED,
            "visibility" -> "published",
            "path" -> "/morgan/baby",
            "hasCollaborators" -> false,
            "subscribedToUpdates" -> false,
            "collaborators" -> Json.arr(),
            "membership" -> Json.obj(
              "access" -> LibraryAccess.OWNER,
              "listed" -> true,
              "subscribed" -> false,
              "permissions" -> permissionCommander.getLibraryPermissions(lib.id.get, user.id)
            )
          )
        }
      }
    }

    "get libraries" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib1, lib2, lib3, lib4, orgGeneralLib) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().withName("Morgan", "Freeman").withUsername("morgan").saved
          val lib1 = LibraryFactory.library().withName("Million Dollar Baby").withOwner(user1).withVisibility(LibraryVisibility.PUBLISHED).withSlug("baby").withColor(LibraryColor.RED).saved

          val user2 = UserFactory.user().withName("Michael", "Caine").withUsername("michael").saved
          // Give READ_INSERT access to Freeman
          val lib2 = LibraryFactory.library().withName("Dark Knight").withOwner(user2).withVisibility(LibraryVisibility.PUBLISHED).withSlug("darkknight").withColor(LibraryColor.BLUE).saved
          LibraryMembershipFactory.membership().withLibraryCollaborator(lib2, user1).saved

          // Give READ_ONLY access to Freeman
          val lib3 = LibraryFactory.library().withName("Now You See Me").withOwner(user2).withVisibility(LibraryVisibility.SECRET).withSlug("magic").withColor(LibraryColor.GREEN).saved
          LibraryMembershipFactory.membership().withLibraryFollower(lib3, user1).saved

          // Caine and Freeman belong to an organization with a library open to organization members
          val org = OrganizationFactory.organization().withName("Braff").withHandle(OrganizationHandle("braff")).withOwner(user2).withMembers(Seq(user1)).saved
          val lib4 = LibraryFactory.library().withName("Going In Style").withOwner(user2).withOrganization(org).withVisibility(LibraryVisibility.ORGANIZATION).withOrgMemberCollaborativePermission(Some(LibraryAccess.READ_WRITE)).withSlug("robbers").withColor(LibraryColor.SKY_BLUE).saved

          val orgGeneralLib = libraryRepo.getBySpaceAndKind(org.id.get, LibraryKind.SYSTEM_ORG_GENERAL).head

          (user1, user2, lib1, lib2, lib3, lib4, orgGeneralLib)
        }
        implicit val config = inject[PublicIdConfiguration]
        val pubId1 = Library.publicId(lib1.id.get).id
        val pubId2 = Library.publicId(lib2.id.get).id
        val pubId4 = Library.publicId(lib4.id.get).id

        val basicUserRepo = inject[BasicUserRepo]
        val result = getLibraries(user1)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        db.readOnlyMaster { implicit session =>
          val payload = Json.parse(contentAsString(result))
          val librariesJson = (payload \ "libraries").as[Seq[JsObject]]
          librariesJson.length === 4
          librariesJson.map(j => (j \ "name").as[String]).toSet === Set(lib1, lib2, lib4, orgGeneralLib).map(_.name)
        }
      }
    }

    "create library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user1 = db.readWrite { implicit s =>
          UserFactory.user().withName("U", "1").withUsername("test").saved
        }
        implicit val config = inject[PublicIdConfiguration]

        // add new library
        status(createLibrary(user1, Json.obj("name" -> "Lib 1", "visibility" -> "secret"))) === OK
        db.readOnlyMaster { implicit s =>
          val lib = libraryRepo.getBySpaceAndSlug(LibrarySpace.fromUserId(user1.id.get), LibrarySlug("lib-1"))
          lib.get.name === "Lib 1"
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib.get.id.get, user1.id.get).get.access === LibraryAccess.OWNER
        }

        // duplicate name
        status(createLibrary(user1, Json.obj("name" -> "Lib 1", "visibility" -> "secret"))) === OK

        // invalid name
        status(createLibrary(user1, Json.obj("name" -> "Lib/\" 3", "visibility" -> "secret"))) === BAD_REQUEST

        db.readOnlyMaster { implicit s =>
          libraryRepo.count === 2
        }
      }
    }

    "get library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib1, lib2, mem1, mem2) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().withName("U", "1").withUsername("test").saved
          val user2 = UserFactory.user().withName("U", "2").withUsername("test").saved
          val lib1 = libraryRepo.save(Library(name = "L1", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("l1"), color = Some(LibraryColor.BLUE), memberCount = 1))
          val lib2 = libraryRepo.save(Library(name = "L2", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("l2"), color = Some(LibraryColor.RED), memberCount = 1))
          val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
          val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY, state = LibraryMembershipStates.INACTIVE))
          (user1, user2, lib1, lib2, mem1, mem2)
        }
        implicit val config = inject[PublicIdConfiguration]
        val lib1PubId = Library.publicId(lib1.id.get)
        val lib2PubId = Library.publicId(lib2.id.get)

        Json.parse(contentAsString(getLibrary(user1, lib1PubId))) === Json.obj(
          "name" -> "L1",
          "slug" -> "l1",
          "visibility" -> "secret",
          "color" -> lib1.color,
          "image" -> JsNull,
          "owner" -> BasicUser.fromUser(user1),
          "keeps" -> 0,
          "followers" -> 0,
          "following" -> JsNull,
          "subscribedToUpdates" -> false)

        status(getLibrary(user2, lib2PubId)) === OK

        status(getLibrary(user2, lib1PubId)) === FORBIDDEN

        val lib1Image = db.readWrite { implicit s =>
          libraryMembershipRepo.save(mem2.withState(LibraryMembershipStates.ACTIVE))
          inject[LibraryImageRepo].save(LibraryImage(
            libraryId = lib1.id.get,
            width = 512,
            height = 256,
            positionX = Some(40),
            positionY = Some(50),
            imagePath = ImagePath("path/to/image.png"),
            format = ImageFormat.PNG,
            source = ImageSource.UserUpload,
            sourceFileHash = ImageHash("000"),
            isOriginal = true))
        }

        Json.parse(contentAsString(getLibrary(user2, lib1PubId))) === Json.obj(
          "name" -> "L1",
          "slug" -> "l1",
          "visibility" -> "secret",
          "color" -> lib1.color,
          "image" -> lib1Image.asInfo,
          "owner" -> BasicUser.fromUser(user1),
          "keeps" -> 0,
          "followers" -> 1,
          "following" -> true,
          "subscribedToUpdates" -> false)
      }
    }

    "delete library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib, mem1, mem2) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().withName("U", "1").withUsername("test").saved
          val user2 = UserFactory.user().withName("U", "2").withUsername("test").saved
          val lib = libraryRepo.save(Library(name = "L", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("l"), memberCount = 1))
          val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
          val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user2.id.get, access = LibraryAccess.READ_WRITE))
          libraryRepo.aTonOfRecords.map { l => (l.id, l.state) } === Seq((lib.id, LibraryStates.ACTIVE))
          (user1, user2, lib, mem1, mem2)
        }
        val libPubId = Library.publicId(lib.id.get)(inject[PublicIdConfiguration])

        status(deleteLibrary(user2, libPubId)) === FORBIDDEN

        db.readOnlyMaster { implicit s =>
          libraryRepo.aTonOfRecords.map { l => (l.id, l.state) } === Seq((lib.id, LibraryStates.ACTIVE))
        }

        status(deleteLibrary(user1, libPubId)) === NO_CONTENT

        db.readOnlyMaster { implicit s =>
          libraryRepo.aTonOfRecords.map { l => (l.id, l.state) } === Seq((lib.id, LibraryStates.INACTIVE))
        }
      }
    }

    "join library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib1, lib2) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().withName("U", "1").withUsername("test1").saved
          val user2 = UserFactory.user().withName("U", "2").withUsername("test2").saved

          val lib1 = libraryRepo.save(Library(name = "L1", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("l1"), memberCount = 1))
          val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

          val lib2 = libraryRepo.save(Library(name = "L2", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("l2"), memberCount = 1))
          val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

          libraryMembershipRepo.countWithLibraryId(lib1.id.get) === 1
          libraryMembershipRepo.countWithLibraryId(lib2.id.get) === 1

          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user2.id.get).isEmpty === true
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib2.id.get, user2.id.get).isEmpty === true

          (user1, user2, lib1, lib2)
        }
        val libPubId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])
        val libPubId2 = Library.publicId(lib2.id.get)(inject[PublicIdConfiguration])

        // join a published library
        status(joinLibrary(user2, libPubId1)) === NO_CONTENT
        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user2.id.get).isEmpty === false
          libraryMembershipRepo.countWithLibraryId(lib1.id.get) === 2
        }

        // join again for idempotency
        status(joinLibrary(user2, libPubId1)) === NO_CONTENT
        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user2.id.get).isEmpty === false
          libraryMembershipRepo.countWithLibraryId(lib1.id.get) === 2
        }

        // join a secret library (with no invite)
        status(joinLibrary(user2, libPubId2)) === FORBIDDEN
        db.readWrite { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib2.id.get, user2.id.get).isEmpty === true
          libraryMembershipRepo.countWithLibraryId(lib2.id.get) === 1
          libraryInviteRepo.save(LibraryInvite(libraryId = lib2.id.get, inviterId = user1.id.get, userId = Some(user2.id.get), access = LibraryAccess.READ_ONLY))
        }

        // join a secret library (with invite)
        status(joinLibrary(user2, libPubId2)) === NO_CONTENT
        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib2.id.get, user2.id.get).isEmpty === false
          libraryMembershipRepo.countWithLibraryId(lib2.id.get) === 2
        }
      }
    }

    "leave library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib1, lib2) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().withName("U", "1").withUsername("test1").saved
          val user2 = UserFactory.user().withName("U", "2").withUsername("test2").saved

          val lib1 = libraryRepo.save(Library(name = "L1", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("l1"), memberCount = 1))
          val mem11 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
          val mem12 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY))

          val lib2 = libraryRepo.save(Library(name = "L2", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("l2"), memberCount = 1))
          val mem21 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user2.id.get, None).get.state === LibraryMembershipStates.ACTIVE
          libraryMembershipRepo.countWithLibraryId(lib1.id.get) === 2
          libraryMembershipRepo.countWithLibraryId(lib2.id.get) === 1
          (user1, user2, lib1, lib2)
        }
        val libPubId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])
        val libPubId2 = Library.publicId(lib2.id.get)(inject[PublicIdConfiguration])

        // leave a library
        status(leaveLibrary(user2, libPubId1)) === NO_CONTENT
        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user2.id.get, None).get.state === LibraryMembershipStates.INACTIVE
          libraryMembershipRepo.countWithLibraryId(lib1.id.get) === 1
        }

        // leave a library again
        status(leaveLibrary(user2, libPubId1)) === NO_CONTENT
        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user2.id.get, None).get.state === LibraryMembershipStates.INACTIVE
          libraryMembershipRepo.countWithLibraryId(lib1.id.get) === 1
        }

        // leave a library (no membership)
        status(leaveLibrary(user2, libPubId2)) === NO_CONTENT

        // leave a library when owner
        status(leaveLibrary(user1, libPubId1)) === BAD_REQUEST
      }
    }

    "add keep to library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib1, lib2, lib3) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().withName("Kanye", "West").withUsername("kanye").saved
          val lib1 = libraryRepo.save(Library(name = "Genius", ownerId = user1.id.get, slug = LibrarySlug("genius"), visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

          val user2 = UserFactory.user().withName("Taylor", "Swift").withUsername("taylor").saved
          val lib2 = libraryRepo.save(Library(name = "My VMA Award", ownerId = user2.id.get, slug = LibrarySlug("myvma"), visibility = LibraryVisibility.SECRET, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user2.id.get, access = LibraryAccess.OWNER))
          val lib3 = libraryRepo.save(Library(name = "New Album", ownerId = user2.id.get, slug = LibrarySlug("newalbum"), visibility = LibraryVisibility.SECRET, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib3.id.get, userId = user2.id.get, access = LibraryAccess.OWNER))

          // Kayne West has membership to these libraries... for some reason
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user1.id.get, access = LibraryAccess.READ_WRITE))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib3.id.get, userId = user1.id.get, access = LibraryAccess.READ_ONLY))
          (user1, user2, lib1, lib2, lib3)
        }

        implicit val config = inject[PublicIdConfiguration]
        val pubId1 = Library.publicId(lib1.id.get)
        val pubId2 = Library.publicId(lib2.id.get)
        val pubId3 = Library.publicId(lib3.id.get)

        // keep to own library
        val result1 = addKeep(user1, pubId1, Json.obj(
          "title" -> "kayne-fidence",
          "url" -> "http://www.imagenius.com",
          "guided" -> false))
        status(result1) === OK
        contentType(result1) must beSome("application/json")
        val keep1 = db.readOnlyMaster { implicit s => keepRepo.pageByLibrary(lib1.id.get, 0, 10).head }
        contentAsString(result1) === s"""{"id":"${keep1.externalId}","mine":true,"removable":true,"visibility":"published","libraryId":"${pubId1.id}","title":"kayne-fidence"}"""

        // keep to someone else's library
        val result2 = addKeep(user1, pubId2, Json.obj(
          "title" -> "T 2",
          "url" -> "http://www.beyonceisbetter.com",
          "guided" -> false))
        status(result2) === OK
        contentType(result2) must beSome("application/json")
        val keep2 = db.readOnlyMaster { implicit s => keepRepo.pageByLibrary(lib2.id.get, 0, 10).head }
        contentAsString(result2) === s"""{"id":"${keep2.externalId}","mine":true,"removable":true,"secret":true,"visibility":"secret","libraryId":"${pubId2.id}","title":"T 2"}"""

        // keep to someone else's library again (should be idempotent)
        val result3 = addKeep(user1, pubId2, Json.obj(
          "title" -> "T 3",
          "url" -> "http://www.beyonceisbetter.com",
          "guided" -> false))
        status(result3) === OK
        contentAsString(result3) === s"""{"id":"${keep2.externalId}","mine":true,"removable":true,"secret":true,"visibility":"secret","libraryId":"${pubId2.id}","title":"T 3"}"""

        // try to keep to someone else's library without sufficient access
        val result4 = addKeep(user1, pubId3, Json.obj(
          "title" -> "IMMA LET YOU FINISH",
          "url" -> "http://www.beyonceisbetter.com",
          "guided" -> false))
        status(result4) === FORBIDDEN
      }
    }

    "join an org library as a collaborator if a user is in that org and the lib has that permission set" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (libOwner, member, collabLib, followerLibs) = db.readWrite { implicit s =>
          val orgOwner = UserFactory.user().saved
          val libOwner = UserFactory.user().saved
          val member = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(orgOwner).withMembers(Seq(libOwner, member)).saved

          val collabLib = LibraryFactory.library().withOwner(libOwner).withOrganization(org).withOrgMemberCollaborativePermission(Some(LibraryAccess.READ_WRITE)).orgVisible().saved

          val followerLib1 = LibraryFactory.library().withOwner(libOwner).withOrganization(org).withOrgMemberCollaborativePermission(Some(LibraryAccess.READ_ONLY)).published().saved
          val followerLib2 = LibraryFactory.library().withOwner(libOwner).withOrganization(org).withOrgMemberCollaborativePermission(Some(LibraryAccess.READ_ONLY)).published().saved
          val followerLib3 = LibraryFactory.library().withOwner(libOwner).published().saved
          (libOwner, member, collabLib, Seq(followerLib1, followerLib2, followerLib3))
        }

        implicit val publicIdConfig = inject[PublicIdConfiguration]
        inject[FakeUserActionsHelper].setUser(member)

        val collabLibId = Library.publicId(collabLib.id.get)
        val collabRequest = request(routes.ExtLibraryController.joinLibrary(collabLibId))
        val collabResponse = controller.joinLibrary(collabLibId)(collabRequest)
        status(collabResponse) === NO_CONTENT
        db.readOnlyMaster { implicit session =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(collabLib.id.get, member.id.get).exists(_.isCollaborator) === true
        }

        for (followerLib <- followerLibs) {
          val followerLibId = Library.publicId(followerLib.id.get)
          val followerRequest = request(routes.ExtLibraryController.joinLibrary(followerLibId))
          val followerResponse = controller.joinLibrary(followerLibId)(followerRequest)
          status(followerResponse) === NO_CONTENT
          db.readOnlyMaster { implicit session =>
            libraryMembershipRepo.getWithLibraryIdAndUserId(followerLib.id.get, member.id.get).exists(_.isCollaborator) === false
          }
        }
        1 === 1
      }
    }

    "get keep in library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib, mem1, mem2, keep) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().withName("U", "1").withUsername("test").saved
          val user2 = UserFactory.user().withName("U", "2").withUsername("test").saved
          val lib = libraryRepo.save(Library(name = "L", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("l"), memberCount = 1))
          val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
          val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY))
          val keep = keepInLibrary(user1, lib, "http://foo.com", "Ya", Seq("Do run run", "Yeah"))
          (user1, user2, lib, mem1, mem2, keep)
        }
        val libPubId = Library.publicId(lib.id.get)(inject[PublicIdConfiguration])

        // user can get own keep in own library
        val result1 = getKeep(user1, libPubId, keep.externalId)
        status(result1) === OK
        contentType(result1) must beSome("application/json")
        contentAsString(result1) === """{"title":"Ya","note":"[#Do run run] [#Yeah]","tags":["Do run run","Yeah"]}"""

        // invalid keep ID
        val result2 = getKeep(user1, libPubId, ExternalId())
        status(result2) === NOT_FOUND
        contentType(result2) must beSome("application/json")
        contentAsString(result2) === """{"error":"keep_not_found"}"""

        // other user with library access can get keep
        val result3 = getKeep(user2, libPubId, keep.externalId)
        status(result3) === OK
        contentType(result3) must beSome("application/json")
        contentAsString(result3) === """{"title":"Ya","note":"[#Do run run] [#Yeah]","tags":["Do run run","Yeah"]}"""

        // other user with library access revoked cannot get keep
        db.readWrite { implicit s => libraryMembershipRepo.save(mem2.withState(LibraryMembershipStates.INACTIVE)) }
        val result4 = getKeep(user2, libPubId, keep.externalId)
        status(result4) === FORBIDDEN
        contentType(result4) must beSome("application/json")
        contentAsString(result4) === """{"error":"library_access_denied"}"""
      }
    }

    "remove keep from library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val (user1, user2, lib1, lib2, keep1, keep2, keep3) = db.readWrite { implicit s =>

          val user1 = UserFactory.user().withName("Colin", "Kaepernick").withCreatedAt(t1).withUsername("qb").saved
          val lib1 = libraryRepo.save(Library(name = "49ers UberL33t Football Plays", ownerId = user1.id.get, slug = LibrarySlug("football"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, createdAt = t1))
          val lib2 = libraryRepo.save(Library(name = "shoes", ownerId = user1.id.get, slug = LibrarySlug("shoes"), visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, createdAt = t1))

          // coach has RW access to keep's football library
          val user2 = UserFactory.user().withName("Jim", "Harbaugh").withCreatedAt(t1).withUsername("coach").saved
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user2.id.get, access = LibraryAccess.READ_WRITE, createdAt = t1))

          val uri1 = uriRepo.save(NormalizedURI.withHash("www.runfast.com", Some("Run")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("www.throwlong.com", Some("Throw")))
          val uri3 = uriRepo.save(NormalizedURI.withHash("www.howtonotchoke.com", Some("DontChoke")))

          val keep1 = KeepFactory.keep().withTitle("Run").withUser(user1).withUri(uri1).withLibrary(lib1).saved
          val keep2 = KeepFactory.keep().withTitle("Throw").withUser(user1).withUri(uri2).withLibrary(lib1).saved
          val keep3 = KeepFactory.keep().withTitle("DontChoke").withUser(user1).withUri(uri3).withLibrary(lib1).saved
          (user1, user2, lib1, lib2, keep1, keep2, keep3)
        }
        val pubId1 = Library.publicId(lib1.id.get)
        val pubId2 = Library.publicId(lib2.id.get)

        db.readOnlyMaster { implicit s => keepRepo.count } === 3

        // test unkeep from own library
        status(removeKeep(user1, pubId1, keep1.externalId)) === NO_CONTENT
        db.readOnlyMaster { implicit s => keepRepo.pageByLibrary(lib1.id.get, 0, 10).map(_.title.get) === Seq("DontChoke", "Throw") }

        // test unkeep from own library again (should be idempotent)
        status(removeKeep(user1, pubId1, keep1.externalId)) === BAD_REQUEST // keep is no longer in that library, so we cannot unkeep it
        db.readOnlyMaster { implicit s => keepRepo.pageByLibrary(lib1.id.get, 0, 10).map(_.title.get) === Seq("DontChoke", "Throw") }

        // test incorrect unkeep from own library (keep exists but in wrong library)
        status(removeKeep(user1, pubId2, keep2.externalId)) === BAD_REQUEST
        db.readOnlyMaster { implicit s => keepRepo.pageByLibrary(lib1.id.get, 0, 10).map(_.title.get) === Seq("DontChoke", "Throw") }

        // test unkeep from someone else's library (have RW access)
        status(removeKeep(user2, pubId1, keep3.externalId)) === NO_CONTENT
        db.readOnlyMaster { implicit s => keepRepo.pageByLibrary(lib1.id.get, 0, 10).map(_.title.get) === Seq("Throw") }
      }
    }

    "update keep in library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib, mem1, mem2, keep) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().withName("U", "1").withUsername("test").saved
          val user2 = UserFactory.user().withName("U", "2").withUsername("test").saved
          val lib = libraryRepo.save(Library(name = "L", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("l"), memberCount = 1))
          val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
          val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY))
          val keep = keepInLibrary(user1, lib, "http://foo.com", "Foo")
          (user1, user2, lib, mem1, mem2, keep)
        }
        val libPubId = Library.publicId(lib.id.get)(inject[PublicIdConfiguration])

        // user can update own keep in own library
        status(updateKeep(user1, libPubId, keep.externalId, Json.obj("title" -> "Bar"))) === NO_CONTENT
        db.readOnlyMaster { implicit s => keepRepo.get(keep.id.get).title.get === "Bar" }

        // invalid keep ID
        status(updateKeep(user1, libPubId, ExternalId(), Json.obj("title" -> "Cat"))) === NOT_FOUND
        db.readOnlyMaster { implicit s => keepRepo.get(keep.id.get).title.get === "Bar" }

        // other user with read-only library access cannot update keep
        status(updateKeep(user2, libPubId, keep.externalId, Json.obj("title" -> "pwned"))) === FORBIDDEN
        db.readOnlyMaster { implicit s => keepRepo.get(keep.id.get).title.get === "Bar" }

        // other user with write access can update keep
        db.readWrite { implicit s => libraryMembershipRepo.save(mem2.copy(access = LibraryAccess.READ_WRITE)) }
        status(updateKeep(user2, libPubId, keep.externalId, Json.obj("title" -> "Dat"))) === NO_CONTENT
        db.readOnlyMaster { implicit s => keepRepo.get(keep.id.get).title.get === "Dat" }
      }
    }

    "update keep note in library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, keep1, lib1) = db.readWrite { implicit s =>
          val user = UserFactory.user().withUsername("spiderman").saved
          val lib = LibraryFactory.library().withOwner(user).saved
          val keep = KeepFactory.keep().withUser(user).withLibrary(lib).saved
          (user, keep, lib)
        }
        val libPubId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])

        // empty note -> nonempty note (no hashtags)
        status(editKeepNote(user1, libPubId1, keep1.externalId, Json.obj("note" -> "thwip!"))) === NO_CONTENT
        db.readOnlyMaster { implicit s =>
          val keep = keepRepo.getOpt(keep1.externalId).get
          keep.note === Some("thwip!")
          tagCommander.getTagsForKeep(keep.id.get) === Seq.empty
        }

        // nonempty note -> nonempty note (with hashtags)
        status(editKeepNote(user1, libPubId1, keep1.externalId, Json.obj("note" -> "thwip! #lol [#tony[sucks\\]] [#avengers] blah"))) === NO_CONTENT
        db.readOnlyMaster { implicit s =>
          val keep = keepRepo.getOpt(keep1.externalId).get
          keep.note === Some("thwip! #lol [#tony[sucks\\]] [#avengers] blah")
          tagCommander.getTagsForKeep(keep.id.get).map(_.tag).toSet === Set("tony[sucks]", "avengers")
        }

        // nonempty note (with hashtags) -> empty note
        status(editKeepNote(user1, libPubId1, keep1.externalId, Json.obj("note" -> ""))) === NO_CONTENT
        db.readOnlyMaster { implicit s =>
          val keep = keepRepo.getOpt(keep1.externalId).get
          keep.note === None
          tagCommander.getTagsForKeep(keep.id.get) === Seq.empty
        }

        // empty note -> non-empty note (with "fake" hashtag)
        status(editKeepNote(user1, libPubId1, keep1.externalId, Json.obj("note" -> "[\\#trololol]"))) === NO_CONTENT
        db.readOnlyMaster { implicit s =>
          val keep = keepRepo.getOpt(keep1.externalId).get
          keep.note === Some("[\\#trololol]")
          tagCommander.getTagsForKeep(keep.id.get) === Seq.empty
        }
      }
    }

    "search tags" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib, mem1, mem2, keep1, keep2) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().withName("U", "1").withUsername("test").saved
          val user2 = UserFactory.user().withName("U", "2").withUsername("test").saved
          val lib = libraryRepo.save(Library(name = "L", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("l"), memberCount = 1))
          val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
          val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY))
          val keep1 = keepInLibrary(user1, lib, "http://foo.com", "Foo", Seq("animal", "aardvark", "Awesome"))
          val keep2 = keepInLibrary(user1, lib, "http://bar.com", "Bar")
          (user1, user2, lib, mem1, mem2, keep1, keep2)
        }
        val libPubId = Library.publicId(lib.id.get)(inject[PublicIdConfiguration])

        // user can search new tags for keeps
        contentAsString(searchTags(user1, libPubId, keep1.externalId, "a", 2)) === """[]"""
        contentAsString(searchTags(user1, libPubId, keep2.externalId, "a", 2)) === """[{"tag":"aardvark","matches":[[0,1]]},{"tag":"animal","matches":[[0,1]]}]"""
        contentAsString(searchTags(user1, libPubId, keep2.externalId, "s", 2)) === """[]"""

        /* todo(Léo): reconsider when tags have been figured out from a product perspective
        // other user with read access to library can search tags
        contentAsString(searchTags(user2, libPubId, "a", 3)) === """[{"tag":"aardvark","matches":[[0,1]]},{"tag":"animal","matches":[[0,1]]},{"tag":"Awesome","matches":[[0,1]]}]"""
        contentAsString(searchTags(user2, libPubId, "s", 3)) === """[]"""
        */

        // other user without read access to library cannot search tags
        db.readWrite { implicit s => libraryMembershipRepo.save(mem2.copy(state = LibraryMembershipStates.INACTIVE)) }
        status(searchTags(user2, libPubId, keep1.externalId, "a", 3)) === FORBIDDEN
      }
    }
  }

  private def getLibraries(user: User)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.getLibraries(allowOpenCollab = true)(request(routes.ExtLibraryController.getLibraries(allowOpenCollab = true)))
  }

  private def createLibrary(user: User, body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.createLibrary()(request(routes.ExtLibraryController.createLibrary()).withBody(body))
  }

  private def getLibrary(user: User, libraryId: PublicId[Library])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.getLibrary(libraryId)(request(routes.ExtLibraryController.getLibrary(libraryId)))
  }

  private def deleteLibrary(user: User, libraryId: PublicId[Library])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.deleteLibrary(libraryId)(request(routes.ExtLibraryController.deleteLibrary(libraryId)))
  }

  private def joinLibrary(user: User, libraryId: PublicId[Library])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.joinLibrary(libraryId)(request(routes.ExtLibraryController.joinLibrary(libraryId)))
  }

  private def leaveLibrary(user: User, libraryId: PublicId[Library])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.leaveLibrary(libraryId)(request(routes.ExtLibraryController.leaveLibrary(libraryId)))
  }

  private def addKeep(user: User, libraryId: PublicId[Library], body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.addKeep(libraryId)(request(routes.ExtLibraryController.addKeep(libraryId)).withBody(body))
  }

  private def getKeep(user: User, libraryId: PublicId[Library], keepId: ExternalId[Keep])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.getKeep(libraryId, keepId, None)(request(routes.ExtLibraryController.getKeep(libraryId, keepId)))
  }

  private def removeKeep(user: User, libraryId: PublicId[Library], keepId: ExternalId[Keep])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.removeKeep(libraryId, keepId)(request(routes.ExtLibraryController.removeKeep(libraryId, keepId)))
  }

  private def updateKeep(user: User, libraryId: PublicId[Library], keepId: ExternalId[Keep], body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.updateKeep(libraryId, keepId)(request(routes.ExtLibraryController.updateKeep(libraryId, keepId)).withBody(body))
  }

  private def editKeepNote(user: User, libraryId: PublicId[Library], keepId: ExternalId[Keep], body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.editKeepNote(libraryId, keepId)(request(routes.ExtLibraryController.editKeepNote(libraryId, keepId)).withBody(body))
  }

  private def searchTags(user: User, libraryId: PublicId[Library], keepId: ExternalId[Keep], q: String, n: Int)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.suggestTags(libraryId, keepId, Some(q), n)(request(routes.ExtLibraryController.suggestTags(libraryId, keepId, Some(q), n)))
  }

  private def keepInLibrary(user: User, lib: Library, url: String, title: String, tags: Seq[String] = Seq.empty)(implicit injector: Injector, session: RWSession): Keep = {
    val uri = uriRepo.save(NormalizedURI(url = url, urlHash = UrlHash(url.hashCode.toString)))
    val keep = KeepFactory.keep().withUser(user).withLibrary(lib).withUrl(url).withTitle(title).saved
    tagCommander.addTagsToKeep(keep.id.get, tags.map(Hashtag(_)), keep.userId, None)
    keep
  }

  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def controller(implicit injector: Injector) = inject[ExtLibraryController]
  private def request(route: Call) = FakeRequest(route.method, route.url)
}
