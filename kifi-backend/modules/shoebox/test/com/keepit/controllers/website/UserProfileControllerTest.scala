package com.keepit.controllers.website

import java.io.File

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.abook.model.EmailAccountInfo
import com.keepit.commanders.{ LibraryImageCommander, FriendStatusCommander }
import com.keepit.common.controller.{ FakeUserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.ImageSize
import com.keepit.graph.FakeGraphServiceClientImpl
import com.keepit.graph.model.{ SociallyRelatedEntitiesForUser, RelatedEntities }
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.KeepFactory.{ keep, keeps }
import com.keepit.model.LibraryInviteFactory._
import com.keepit.model.LibraryInviteFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserConnectionFactoryHelper._
import com.keepit.model.UserConnectionFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.keepit.model.OrganizationFactory._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model._
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.io.FileUtils
import com.keepit.common.time._

import org.specs2.mutable.Specification
import play.api.libs.Files.TemporaryFile

import play.api.libs.json._
import play.api.mvc.{ Call, Result }
import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
import scala.slick.jdbc.StaticQuery

class UserProfileControllerTest extends Specification with ShoeboxTestInjector {

  val controllerTestModules = Seq(
    FakeABookServiceClientModule(),
    FakeSocialGraphModule())

  "UserProfileController" should {

    "load profile users" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, user3, user4, user5, lib1) = db.readWrite { implicit session =>
          val user1 = user().withName("George", "Washington").withUsername("GDubs").withPictureName("pic1").saved
          val user2 = user().withName("Abe", "Lincoln").withUsername("abe").saved
          val user3 = user().withName("Thomas", "Jefferson").withUsername("TJ").saved
          val user4 = user().withName("John", "Adams").withUsername("jayjayadams").saved
          val user5 = user().withName("Ben", "Franklin").withUsername("Benji").saved

          connect(user1 -> user2,
            user1 -> user3,
            user4 -> user1,
            user2 -> user3).saved

          val user1secretLib = libraries(3).map(_.withOwner(user1).secret()).saved.head.savedFollowerMembership(user2)

          val user1lib = library().withOwner(user1).published().saved.savedFollowerMembership(user5, user4)
          user1lib.visibility === LibraryVisibility.PUBLISHED

          val user3lib = library().withOwner(user3).published().saved.savedFollowerMembership(user2)
          val user5lib = library().withOwner(user5).published().saved.savedFollowerMembership(user1)
          membership().withLibraryFollower(library().withOwner(user5).published().saved, user1).unlisted().saved

          invite().fromLibraryOwner(user3lib).toUser(user1.id.get).withState(LibraryInviteStates.ACTIVE).saved
          invite().fromLibraryOwner(user3lib).toUser(user1.id.get).withState(LibraryInviteStates.ACTIVE).saved // duplicate library invite
          invite().fromLibraryOwner(user5lib).toUser(user1.id.get).withState(LibraryInviteStates.ACCEPTED).saved

          keeps(2).map(_.withUser(user1).withLibrary(user1secretLib)).saved
          keeps(3).map(_.withUser(user1).withLibrary(user1lib)).saved
          keep().withUser(user3).withLibrary(user3lib).saved

          (user1, user2, user3, user4, user5, user1lib)
        }

        def basicUserWFS(user: User, viewerIdOpt: Option[Id[User]])(implicit session: RSession): BasicUserWithFriendStatus = {
          val basicUser = BasicUser.fromUser(user)
          viewerIdOpt map { viewerId =>
            inject[FriendStatusCommander].augmentUser(viewerId, user.id.get, basicUser)
          } getOrElse BasicUserWithFriendStatus.fromWithoutFriendStatus(basicUser)
        }
        db.readOnlyMaster { implicit s =>
          controller.loadProfileUser(user1.id.get, basicUserWFS(user1, user2.id), user2.id, None) === Json.obj(
            "id" -> user1.externalId,
            "firstName" -> user1.firstName,
            "lastName" -> user1.lastName,
            "pictureName" -> "pic1.jpg",
            "username" -> user1.username.value,
            "isFriend" -> true,
            "libraries" -> 2, "connections" -> 3, "followers" -> 3,
            "mLibraries" -> 1, "mConnections" -> 1
          )
          controller.loadProfileUser(user1.id.get, basicUserWFS(user1, user2.id), user2.id, user3.id) === Json.obj(
            "id" -> user1.externalId,
            "firstName" -> user1.firstName,
            "lastName" -> user1.lastName,
            "pictureName" -> "pic1.jpg",
            "username" -> user1.username.value,
            "isFriend" -> true,
            "libraries" -> 2, "connections" -> 3, "followers" -> 3,
            "mLibraries" -> 1, "mConnections" -> 1
          )
          controller.loadProfileUser(user1.id.get, basicUserWFS(user1, None), None, None) === Json.obj(
            "id" -> user1.externalId,
            "firstName" -> user1.firstName,
            "lastName" -> user1.lastName,
            "pictureName" -> "pic1.jpg",
            "username" -> user1.username.value,
            "libraries" -> 1,
            "connections" -> 3, "followers" -> 2
          )
          controller.loadProfileUser(user2.id.get, basicUserWFS(user2, user3.id), user3.id, None) === Json.obj(
            "id" -> user2.externalId,
            "firstName" -> user2.firstName,
            "lastName" -> user2.lastName,
            "pictureName" -> "0.jpg",
            "username" -> user2.username.value,
            "isFriend" -> true,
            "libraries" -> 0, "connections" -> 2, "followers" -> 0,
            "mLibraries" -> 0, "mConnections" -> 1
          )
          controller.loadProfileUser(user2.id.get, basicUserWFS(user2, None), None, None) === Json.obj(
            "id" -> user2.externalId,
            "firstName" -> user2.firstName,
            "lastName" -> user2.lastName,
            "pictureName" -> "0.jpg",
            "username" -> user2.username.value,
            "libraries" -> 0, "connections" -> 2, "followers" -> 0
          )
          controller.loadProfileUser(user2.id.get, basicUserWFS(user2, user2.id), user2.id, user3.id) === Json.obj(
            "id" -> user2.externalId,
            "firstName" -> user2.firstName,
            "lastName" -> user2.lastName,
            "pictureName" -> "0.jpg",
            "username" -> user2.username.value,
            "libraries" -> 0, "connections" -> 2, "followers" -> 0,
            "mLibraries" -> 1, "mConnections" -> 1
          )
        }
        db.readWrite { implicit s =>
          val repo = inject[UserConnectionRepo]
          repo.deactivateAllConnections(user2.id.get)
        }
        db.readOnlyMaster { implicit s =>
          controller.loadProfileUser(user1.id.get, basicUserWFS(user1, user2.id), user2.id, None) === Json.obj(
            "id" -> user1.externalId,
            "firstName" -> user1.firstName,
            "lastName" -> user1.lastName,
            "pictureName" -> "pic1.jpg",
            "username" -> user1.username.value,
            "isFriend" -> false,
            "libraries" -> 2, "connections" -> 2, "followers" -> 3,
            "mLibraries" -> 1, "mConnections" -> 0
          )
        }
      }
    }

    "get profile" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, user3, user4, user5, lib1, user5lib, org1, org2) = db.readWrite { implicit session =>
          val user1 = user().withName("George", "Washington").withUsername("GDubs").withPictureName("pic1").saved
          val user2 = user().withName("Abe", "Lincoln").withUsername("abe").saved
          val user3 = user().withName("Thomas", "Jefferson").withUsername("TJ").saved
          val user4 = user().withName("John", "Adams").withUsername("jayjayadams").saved
          val user5 = user().withName("Ben", "Franklin").withUsername("Benji").saved

          val org = OrganizationFactory.organization().withName("America").withOwner(user1).saved
          val org2 = OrganizationFactory.organization().withName("Canada").withOwner(user5).withInvitedUsers(Seq(user1)).saved

          inject[UserValueRepo].save(UserValue(userId = user1.id.get, name = UserValueName.USER_DESCRIPTION, value = "First Prez yo!"))
          connect(user1 -> user2,
            user1 -> user3,
            user4 -> user1,
            user2 -> user3).saved

          val user1secretLib = libraries(3).map(_.withOwner(user1).withKind(LibraryKind.USER_CREATED).withKeepCount(3).withMemberCount(4).secret()).saved.head.savedFollowerMembership(user2)

          val user1lib = library().withOwner(user1).withKind(LibraryKind.USER_CREATED).withKeepCount(3).published().withMemberCount(4).withOrganizationIdOpt(org.id).saved.savedFollowerMembership(user5, user4)
          user1lib.visibility === LibraryVisibility.PUBLISHED

          val user3lib = library().withOwner(user3).published().withKind(LibraryKind.USER_CREATED).withKeepCount(3).withMemberCount(4).saved
          val user5lib = library().withOwner(user5).published().withKind(LibraryKind.USER_CREATED).withKeepCount(3).withMemberCount(4).saved.savedFollowerMembership(user1)
          keep().withUser(user5).withLibrary(user5lib).saved
          membership().withLibraryFollower(library().withOwner(user5).published().withKind(LibraryKind.USER_CREATED).withKeepCount(3).saved, user1).unlisted().saved

          invite().fromLibraryOwner(user3lib).toUser(user1.id.get).withState(LibraryInviteStates.ACTIVE).saved
          invite().fromLibraryOwner(user3lib).toUser(user1.id.get).withState(LibraryInviteStates.ACTIVE).saved // duplicate library invite
          invite().fromLibraryOwner(user5lib).toUser(user1.id.get).withState(LibraryInviteStates.ACCEPTED).saved

          keeps(2).map(_.withUser(user1).withLibrary(user1secretLib)).saved
          keeps(3).map(_.withUser(user1).withLibrary(user1lib)).saved
          keep().withUser(user3).withLibrary(user3lib).saved

          (user1, user2, user3, user4, user5, user1lib, user5lib, org, org2)
        }
        db.readOnlyMaster { implicit s =>
          val libMem = libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user4.id.get).get
          libMem.access === LibraryAccess.READ_ONLY
          libMem.state.value === "active"

          import com.keepit.common.db.slick.StaticQueryFixed.interpolation
          val ret1 = sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = 4".as[Int].firstOption.getOrElse(0)
          ret1 === 1
          val ret2 = sql"select count(*) from library_membership lm, library lib where lm.library_id = lib.id and lm.user_id = 4 and lib.state = 'active' and lm.state = 'active' and lm.listed and lib.visibility = 'published'".as[Int].firstOption.getOrElse(0)
          ret2 === 1

          libraryMembershipRepo.countNonTrivialLibrariesWithUserIdAndAccess(user1.id.get, LibraryAccess.OWNER) === 4
          libraryMembershipRepo.countNonTrivialLibrariesWithUserIdAndAccess(user2.id.get, LibraryAccess.OWNER) === 0
          libraryMembershipRepo.countNonTrivialLibrariesWithUserIdAndAccess(user3.id.get, LibraryAccess.OWNER) === 1
          libraryMembershipRepo.countNonTrivialLibrariesWithUserIdAndAccess(user4.id.get, LibraryAccess.OWNER) === 0
          val ret3 = StaticQuery.queryNA[Long](
            s"select l.id from library_membership lm, library l where " +
              s"lm.library_id = l.id and l.kind = 'user_created' and l.last_kept is not null and l.keep_count > 1 and l.state = 'active' and " +
              s"lm.user_id = ${user5.id.get} and lm.access = '${LibraryAccess.OWNER.value}' and lm.state = 'active'")
            .list
          ret3 === Seq(user5lib.id.get.id)
          libraryMembershipRepo.countNonTrivialLibrariesWithUserIdAndAccess(user5.id.get, LibraryAccess.OWNER) === 1

          libraryRepo.countOwnerLibrariesForAnonymous(user1.id.get) === 1
          libraryRepo.countOwnerLibrariesForAnonymous(user2.id.get) === 0
          libraryRepo.countOwnerLibrariesForAnonymous(user3.id.get) === 1
          libraryRepo.countOwnerLibrariesForAnonymous(user4.id.get) === 0
          libraryRepo.countOwnerLibrariesForAnonymous(user5.id.get) === 2
          libraryRepo.countOwnerLibrariesForOtherUser(user1.id.get, user5.id.get) === 1
          libraryRepo.countOwnerLibrariesForOtherUser(user1.id.get, user2.id.get) === 2
          libraryRepo.countOwnerLibrariesForOtherUser(user2.id.get, user5.id.get) === 0
          libraryRepo.countOwnerLibrariesForOtherUser(user3.id.get, user5.id.get) === 1
          libraryRepo.countOwnerLibrariesForOtherUser(user4.id.get, user5.id.get) === 0
          libraryRepo.countOwnerLibrariesForOtherUser(user5.id.get, user1.id.get) === 2
        }

        //non existing username
        status(getProfile(Some(user1), Username("foo"))) must equalTo(NOT_FOUND)

        //seeing a profile from an anonymous user
        val anonViewer = getProfile(None, user1.username)
        status(anonViewer) must equalTo(OK)
        contentType(anonViewer) must beSome("application/json")
        val res1 = contentAsJson(anonViewer)
        (res1 \ "id").as[ExternalId[User]] === user1.externalId
        (res1 \ "firstName").as[String] === "George"
        (res1 \ "numKeeps").as[Int] === 5

        val validatedOrgs = (res1 \ "orgs").validate[Seq[JsObject]]
        validatedOrgs.isSuccess === true
        validatedOrgs.get.length === 0 // we don't expose a user's orgs to anyone but themselves

        val validatedPendingOrgs = (res1 \ "pendingOrgs").validate[Seq[JsObject]]
        validatedPendingOrgs.isSuccess === true
        validatedPendingOrgs.get.length === 0

        //seeing a profile of my own
        val selfViewer = getProfile(Some(user1), user1.username)
        status(selfViewer) must equalTo(OK)
        contentType(selfViewer) must beSome("application/json")
        val res2 = contentAsJson(selfViewer)
        (res2 \ "numLibraries").as[Int] === 4
        (res2 \ "numInvitedLibraries").as[Int] === 1

        //seeing a profile from another user (friend)
        val friendViewer = getProfile(Some(user2), user1.username)
        status(friendViewer) must equalTo(OK)
        contentType(friendViewer) must beSome("application/json")
        val res3 = contentAsJson(friendViewer)
        (res3 \ "isFriend").as[Boolean] === true
      }
    }

    "get profile libraries" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (user1, user2, user3, lib1, lib2, lib3, keep1) = db.readWrite { implicit s =>
          val user1 = user().withName("first", "user").withUsername("firstuser").saved
          val user2 = user().withName("second", "user").withUsername("seconduser").withPictureName("alf").saved
          val user3 = user().withName("third", "user").withUsername("thirduser").withPictureName("asdf").saved
          val library1 = library().withName("lib1").withOwner(user1).published.withSlug("lib1").withMemberCount(11).withColor("blue").withDesc("My first library!").saved.savedFollowerMembership(user2).savedCollaboratorMembership(user3)
          val library2 = library().withName("lib2").withOwner(user2).secret.withSlug("lib2").withMemberCount(22).saved
          val library3 = library().withName("lib3").withOwner(user2).secret.withSlug("lib3").withMemberCount(33).saved.savedFollowerMembership(user1)
          val k1 = keep().withUser(user1).withLibrary(library1).saved
          (user1, user2, user3, library1, library2, library3, k1)
        }

        val pubId1 = Library.publicId(lib1.id.get)
        val pubId2 = Library.publicId(lib2.id.get)
        val pubId3 = Library.publicId(lib3.id.get)

        val (basicUser1, basicUser2) = db.readOnlyMaster { implicit s =>
          (basicUserRepo.load(user1.id.get), basicUserRepo.load(user2.id.get))
        }

        // test viewing my own libraries
        val result1 = getProfileLibraries(Some(user1), user1.username, 0, 10, "own")
        val lib1Updated = db.readOnlyMaster { libraryRepo.get(lib1.id.get)(_) }
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        val result1Json = Json.parse(contentAsString(result1))
        val ownLib = (result1Json \ "own").as[Seq[JsObject]].head
        val libPerms = db.readOnlyMaster { implicit s => permissionCommander.getLibrariesPermissions(Set(lib1.id.get, lib2.id.get, lib3.id.get), user1.id) }
        (ownLib \ "id").as[PublicId[Library]] must equalTo(pubId1)
        (ownLib \ "kind").as[LibraryKind] must equalTo(LibraryKind.USER_CREATED)
        (ownLib \ "visibility").as[LibraryVisibility] must equalTo(LibraryVisibility.PUBLISHED)
        (ownLib \ "membership").as[LibraryMembershipInfo] === LibraryMembershipInfo(LibraryAccess.OWNER, listed = true, subscribed = false, permissions = libPerms(lib1.id.get))
        (ownLib \ "followers").as[Seq[BasicUser]].head.externalId must equalTo(user2.externalId)
        (ownLib \ "collaborators").as[Seq[BasicUser]].head.externalId must equalTo(user3.externalId)

        // test viewing following libraries
        val result2 = getProfileLibraries(Some(user1), user1.username, 0, 10, "following")
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        val result2Json = Json.parse(contentAsString(result2))
        val followingLib = (result2Json \ "following").as[Seq[JsObject]].head
        (followingLib \ "id").as[PublicId[Library]] must equalTo(pubId3)
        (followingLib \ "kind").as[LibraryKind] must equalTo(LibraryKind.USER_CREATED)
        (followingLib \ "visibility").as[LibraryVisibility] must equalTo(LibraryVisibility.SECRET)
        (followingLib \ "membership").as[LibraryMembershipInfo] === LibraryMembershipInfo(LibraryAccess.READ_ONLY, listed = true, subscribed = false, permissions = libPerms(lib3.id.get))

        // test viewing invited libraries
        val result3 = getProfileLibraries(Some(user1), user1.username, 0, 10, "invited")
        status(result3) must equalTo(OK)
        contentType(result3) must beSome("application/json")
        Json.parse(contentAsString(result3)) === Json.parse("""{"invited":[]}""")

        // test all libraries
        val result4 = getProfileLibraries(Some(user1), user1.username, 0, 10, "all")
        status(result4) must equalTo(OK)
        contentType(result4) must beSome("application/json")
        val resultJson4 = contentAsJson(result4)
        (resultJson4 \ "own").as[Seq[JsObject]].length === 1
        (resultJson4 \ "following").as[Seq[JsObject]].length === 1
        (resultJson4 \ "invited").as[Seq[JsObject]].length === 0

        // test viewing other library
        val result5 = getProfileLibraries(Some(user1), user2.username, 0, 10, "following")
        status(result5) must equalTo(OK)
        contentType(result5) must beSome("application/json")
        val result5Json = (contentAsJson(result5) \ "following").as[Seq[JsObject]]
        (result5Json.head \ "name").as[String] === "lib1"
        (result5Json.head \ "numFollowers").as[Int] === 1
      }
    }

    "get profile connections" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, user3, user4, user5) = db.readWrite { implicit session =>
          val user1 = user().withName("George", "Washington").withUsername("GDubs").withPictureName("pic1").saved
          val user2 = user().withName("Abe", "Lincoln").withUsername("abe").saved
          val user3 = user().withName("Thomas", "Jefferson").withUsername("TJ").saved
          val user4 = user().withName("John", "Adams").withUsername("jayjayadams").saved
          val user5 = user().withName("Ben", "Franklin").withUsername("Benji").saved

          connect(user1 -> user3).saved
          connect(user1 -> user4).saved
          connect(user2 -> user4).saved
          (user1, user2, user3, user4, user5)
        }

        val relationship = SociallyRelatedEntitiesForUser(
          RelatedEntities[User, User](user1.id.get, Seq(user4.id.get -> 1, user5.id.get -> 4, user2.id.get -> 2, user3.id.get -> 3), totalSteps = 10),
          RelatedEntities[User, SocialUserInfo](user1.id.get, Seq.empty, totalSteps = 0),
          RelatedEntities[User, SocialUserInfo](user1.id.get, Seq.empty, totalSteps = 0),
          RelatedEntities[User, EmailAccountInfo](user1.id.get, Seq.empty, totalSteps = 0),
          RelatedEntities[User, Organization](user1.id.get, Seq.empty, totalSteps = 0),
          totalSteps = 100000
        )
        inject[FakeGraphServiceClientImpl].setSociallyRelatedEntitiesForUser(user1.id.get, relationship)
        // view as owner
        val result1 = getProfileConnections(Some(user1), Username("GDubs"), 10)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        val resultJson1 = contentAsJson(result1)
        (resultJson1 \ "count") === JsNumber(2)
        (resultJson1 \\ "id") === Seq(user3, user4).map(u => JsString(u.externalId.id))
        (resultJson1 \ "ids") === JsArray()

        // view as anybody
        val result2 = getProfileConnections(Some(user4), Username("GDubs"), 10)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        val resultJson2 = contentAsJson(result2)
        (resultJson2 \ "count") === JsNumber(2)
        (resultJson2 \\ "id") === Seq(user4, user3).map(u => JsString(u.externalId.id))
        (resultJson2 \ "ids") === JsArray()

        (resultJson2 \ "invitations").isInstanceOf[JsUndefined] === true

        db.readWrite { implicit s =>
          friendRequestRepo.save(FriendRequest(senderId = user5.id.get, recipientId = user1.id.get, messageHandle = None))
        }

        val result3 = getProfileConnections(Some(user4), Username("GDubs"), 10)
        status(result3) must equalTo(OK)
        contentType(result3) must beSome("application/json")
        val resultJson3 = contentAsJson(result3)
        (resultJson3 \ "count") === JsNumber(2)
        (resultJson3 \\ "id") === Seq(user4, user3).map(u => JsString(u.externalId.id))
        (resultJson3 \ "ids") === JsArray()

        (resultJson3 \ "invitations").isInstanceOf[JsUndefined] === true

        val result4 = getProfileConnections(Some(user1), Username("GDubs"), 10)
        status(result4) must equalTo(OK)
        contentType(result4) must beSome("application/json")
        val resultJson4 = contentAsJson(result4)
        (resultJson4 \ "count") === JsNumber(2)
        (resultJson4 \ "users" \\ "id") === Seq(user3, user4).map(u => JsString(u.externalId.id))
        (resultJson4 \ "ids") === JsArray()

        (resultJson4 \ "invitations" \\ "id") === Seq(user5).map(u => JsString(u.externalId.id))
      }
    }

    "get profile followers" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, user3, user4, user5) = db.readWrite { implicit session =>
          val user1 = user().withName("George", "Washington").withUsername("GDubs").withPictureName("pic1").saved
          val user2 = user().withName("Abe", "Lincoln").withUsername("abe").saved
          val user3 = user().withName("Thomas", "Jefferson").withUsername("TJ").saved
          val user4 = user().withName("John", "Adams").withUsername("jayjayadams").saved
          val user5 = user().withName("Ben", "Franklin").withUsername("Benji").saved

          libraries(3).map(_.withOwner(user1).secret()).saved.head.savedFollowerMembership(user2, user3) // create 3 secret libraries, one is follower by user2 and user3
          library().withOwner(user1).published().saved.savedFollowerMembership(user5, user4) // create 1 published library, followed by user4 and user 5
          connect(user1 -> user3).saved
          (user1, user2, user3, user4, user5)
        }

        val relationship = SociallyRelatedEntitiesForUser(
          RelatedEntities[User, User](user1.id.get, Seq(user4.id.get -> 1, user5.id.get -> 4, user2.id.get -> 2, user3.id.get -> 3), totalSteps = 10),
          RelatedEntities[User, SocialUserInfo](user1.id.get, Seq.empty, totalSteps = 0),
          RelatedEntities[User, SocialUserInfo](user1.id.get, Seq.empty, totalSteps = 0),
          RelatedEntities[User, EmailAccountInfo](user1.id.get, Seq.empty, totalSteps = 0),
          RelatedEntities[User, Organization](user1.id.get, Seq.empty, totalSteps = 0),
          totalSteps = 100000
        )
        inject[FakeGraphServiceClientImpl].setSociallyRelatedEntitiesForUser(user1.id.get, relationship)

        // view as owner
        val result1 = getProfileFollowers(Some(user1), Username("GDubs"), 2)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        val resultJson1 = contentAsJson(result1)
        (resultJson1 \ "count") === JsNumber(4)
        (resultJson1 \\ "id") === Seq(user3, user5).map(u => JsString(u.externalId.id))
        (resultJson1 \ "ids") === Json.toJson(Seq(user2, user4).map(_.externalId))

        // view as anybody
        val result2 = getProfileFollowers(Some(user4), Username("GDubs"), 10)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        val resultJson2 = contentAsJson(result2)
        (resultJson2 \ "count") === JsNumber(2)
        (resultJson2 \\ "id") === Seq(user4, user5).map(u => JsString(u.externalId.id))
        (resultJson2 \ "ids") === JsArray()

        val result3 = getProfileFollowers(Some(user2), Username("GDubs"), 10)
        status(result3) must equalTo(OK)
        contentType(result3) must beSome("application/json")
        val resultJson3 = contentAsJson(result3)
        (resultJson3 \ "count") === JsNumber(3)
        (resultJson3 \\ "id") === Seq(user2, user4, user5).map(u => JsString(u.externalId.id))
        (resultJson3 \ "ids") === JsArray()

        (resultJson3 \ "invitations").isInstanceOf[JsUndefined] === true

      }
    }

    "get mutual connections" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, user3, user4, user5, user6) = db.readWrite { implicit session =>
          val user1 = user().withName("George", "Washington").withUsername("GDubs").withPictureName("pic1").saved
          val user2 = user().withName("Abe", "Lincoln").withUsername("abe").saved
          val user3 = user().withName("Thomas", "Jefferson").withUsername("TJ").saved
          val user4 = user().withName("John", "Adams").withUsername("jayjayadams").saved
          val user5 = user().withName("Ben", "Franklin").withUsername("Benji").saved
          val user6 = user().withName("Nikola", "Tesla").withUsername("wizard").saved

          connect(user1 -> user2, user1 -> user3, user4 -> user1, user2 -> user3, user2 -> user4, user2 -> user5, user3 -> user5, user2 -> user6).saved

          (user1, user2, user3, user4, user5, user6)
        }

        { // unauthenticated vistor
          val result = getMutualConnections(None, user1.externalId)
          status(result) === FORBIDDEN
        }

        { // self
          val result = getMutualConnections(Some(user1), user1.externalId)
          status(result) === BAD_REQUEST
        }

        { // connected
          val result = getMutualConnections(Some(user1), user2.externalId)
          status(result) === OK
          contentType(result) === Some("application/json")
          val content = contentAsJson(result)
          (content \\ "id") === Seq(user3, user4).map(u => JsString(u.externalId.id))
          (content \\ "connections") === Seq(JsNumber(3), JsNumber(2))
          (content \ "count") === JsNumber(2)
        }

        { // connected, symmetrical
          val result = getMutualConnections(Some(user2), user1.externalId)
          status(result) === OK
          contentType(result) === Some("application/json")
          val content = contentAsJson(result)
          (content \\ "id") === Seq(user4, user3).map(u => JsString(u.externalId.id))
          (content \\ "connections") === Seq(JsNumber(2), JsNumber(3))
          (content \ "count") === JsNumber(2)
        }

        { // connected, no mutual connections
          val result = getMutualConnections(Some(user2), user6.externalId)
          status(result) === OK
          contentType(result) === Some("application/json")
          val content = contentAsJson(result)
          (content \\ "id") === Seq.empty
          (content \\ "connections") === Seq.empty
          (content \ "count") === JsNumber(0)

          (content \ "invitations").isInstanceOf[JsUndefined] === true
        }
      }
    }
  }

  private def getProfile(viewerOpt: Option[User], username: Username)(implicit injector: Injector): Future[Result] = {
    setViewer(viewerOpt)
    controller.getProfile(username)(request(routes.UserProfileController.getProfile(username)))
  }

  private def getProfileLibraries(viewerOpt: Option[User], username: Username, page: Int, size: Int, filter: String)(implicit injector: Injector): Future[Result] = {
    setViewer(viewerOpt)
    controller.getProfileLibraries(username, page, size, filter)(request(routes.UserProfileController.getProfileLibraries(username, page, size, filter)))
  }

  private def getProfileFollowers(viewerOpt: Option[User], username: Username, limit: Int)(implicit injector: Injector): Future[Result] = {
    setViewer(viewerOpt)
    controller.getProfileFollowers(username, limit)(request(routes.UserProfileController.getProfileFollowers(username, limit)))
  }

  private def getProfileConnections(viewerOpt: Option[User], username: Username, limit: Int)(implicit injector: Injector): Future[Result] = {
    setViewer(viewerOpt)
    controller.getProfileConnections(username, limit)(request(routes.UserProfileController.getProfileFollowers(username, limit)))
  }

  private def getMutualConnections(viewerOpt: Option[User], id: ExternalId[User])(implicit injector: Injector): Future[Result] = {
    setViewer(viewerOpt)
    controller.getMutualConnections(id)(request(routes.UserProfileController.getMutualConnections(id)))
  }

  private def controller(implicit injector: Injector) = inject[UserProfileController]
  private def request(route: Call) = FakeRequest(route.method, route.url)
  private def setViewer(viewerOpt: Option[User])(implicit injector: Injector): Unit = {
    viewerOpt match {
      case Some(user) => inject[FakeUserActionsHelper].setUser(user)
      case None => inject[FakeUserActionsHelper].unsetUser()
    }
  }
}
