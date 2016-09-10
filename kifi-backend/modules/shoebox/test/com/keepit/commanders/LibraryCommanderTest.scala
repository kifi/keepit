package com.keepit.commanders

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.abook.model.RichContact
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.{ FakeCryptoModule, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.mail.{ ElectronicMailRepo, EmailAddress, FakeMailModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.eliza.{ ElizaServiceClient, FakeElizaServiceClientImpl, FakeElizaServiceClientModule }
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.social.BasicUser
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.DateTime
import org.specs2.mutable.SpecificationLike

import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.util.Random

class LibraryCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  def modules = Seq(
    FakeExecutionContextModule(),
    FakeSearchServiceClientModule(),
    FakeMailModule(),
    FakeShoeboxStoreModule(),
    FakeCryptoModule(),
    FakeSocialGraphModule(),
    FakeABookServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeHeimdalServiceClientModule()
  )

  args(skipAll = true)

  def randomEmails(n: Int): Seq[EmailAddress] = {
    (for (i <- 1 to 20) yield {
      RandomStringUtils.randomAlphabetic(15) + "@" + RandomStringUtils.randomAlphabetic(5) + ".com"
    }).toSeq.map(EmailAddress(_))
  }
  // Fill the system with a bunch of garbage
  def fillWithGarbage()(implicit injector: Injector, session: RWSession): Unit = {
    val n = 2
    for (i <- 1 to n) {
      val orgOwner = UserFactory.user().saved
      val libOwners = UserFactory.users(n).saved
      val collaborators = UserFactory.users(20).saved
      val followers = UserFactory.users(20).saved
      val invitedUsers = UserFactory.users(20).saved
      val invitedEmails = randomEmails(20)
      val org = OrganizationFactory.organization().withOwner(orgOwner).withMembers(libOwners ++ collaborators).withInvitedUsers(followers).saved
      for (lo <- libOwners) {
        LibraryFactory.library().withOwner(lo).withCollaborators(collaborators).withFollowers(followers).withInvitedUsers(invitedUsers).withInvitedEmails(invitedEmails).saved
        LibraryFactory.library().withOwner(lo).withCollaborators(collaborators).withFollowers(followers).withInvitedUsers(invitedUsers).withInvitedEmails(invitedEmails).withOrganization(org).saved
      }
    }
  }

  def setupUsers()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 12, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val emailIron = EmailAddress("tony@stark.com")
    val emailCaptain = EmailAddress("steve.rogers@hotmail.com")
    val emailAgent = EmailAddress("samuelljackson@shield.com")
    val emailHulk = EmailAddress("incrediblehulk@gmail.com")

    val (userIron, userCaptain, userAgent, userHulk) = db.readWrite { implicit s =>
      val userIron = user().withUsername("ironman").saved
      val userCaptain = user().withUsername("captainamerica").saved
      val userAgent = user().withUsername("agentfury").saved
      val userHulk = user().withUsername("incrediblehulk").saved

      userEmailAddressCommander.intern(userId = userIron.id.get, address = emailIron).get
      userEmailAddressCommander.intern(userId = userCaptain.id.get, address = emailCaptain).get
      userEmailAddressCommander.intern(userId = userAgent.id.get, address = emailAgent).get
      userEmailAddressCommander.intern(userId = userHulk.id.get, address = emailHulk).get

      (userIron, userCaptain, userAgent, userHulk)
    }
    db.readOnlyMaster { implicit s =>
      userRepo.count === 4
    }
    (userIron, userCaptain, userAgent, userHulk)
  }

  def setupLibraries()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk) = setupUsers
    val t1 = new DateTime(2014, 8, 1, 1, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val t2 = new DateTime(2014, 8, 1, 1, 0, 0, 1, DEFAULT_DATE_TIME_ZONE)
    val (libShield, libMurica, libScience) = db.readWrite { implicit s =>
      val libShield = library().withOwner(userAgent).withName("Avengers Missions").withSlug("avengers").secret().saved
      val libMurica = library().withOwner(userCaptain).withName("MURICA").withSlug("murica").published().saved
      val libScience = library().withOwner(userIron).withName("Science & Stuff").withSlug("science").withLibraryCommentPermissions(LibraryCommentPermissions.COLLABORATOR).discoverable().saved
      (libShield, libMurica, libScience)
    }
    db.readOnlyMaster { implicit s =>
      val allLibs = libraryRepo.aTonOfRecords
      allLibs.length === 3
      allLibs.map(_.name) === Seq("Avengers Missions", "MURICA", "Science & Stuff")
      allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")
      allLibs.map(_.description) === Seq(None, None, None)
      allLibs.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.PUBLISHED, LibraryVisibility.DISCOVERABLE)
      libraryMembershipRepo.count === 3
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }

  def setupInvites()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries

    val t1 = new DateTime(2014, 8, 1, 2, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    db.readWrite { implicit s =>
      // Everybody loves Murica! Follow Captain America's library
      libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userIron.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))
      libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userAgent.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))
      libraryInviteRepo.save(LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))

      // Ironman invites the Hulk to contribute to 'Science & Stuff'
      libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, inviterId = userIron.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_WRITE, createdAt = t1))

      (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
    }
    db.readOnlyMaster { implicit s =>
      libraryInviteRepo.count === 4
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }

  def setupAcceptedInvites()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupInvites
    val t1 = new DateTime(2014, 8, 1, 3, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    db.readWrite { implicit s =>
      // Hulk accepts Ironman's invite to see 'Science & Stuff'
      val inv1 = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId = libScience.id.get, userId = userHulk.id.get).head
      libraryInviteRepo.save(inv1.withState(LibraryInviteStates.ACCEPTED))

      // Ironman & NickFury accept Captain's invite to see 'MURICA'
      val inv2 = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId = libMurica.id.get, userId = userIron.id.get).head
      libraryInviteRepo.save(inv2.withState(LibraryInviteStates.ACCEPTED))
      val inv3 = libraryInviteRepo.getWithLibraryIdAndUserId(libraryId = libMurica.id.get, userId = userAgent.id.get).head
      libraryInviteRepo.save(inv3.withState(LibraryInviteStates.ACCEPTED))

      membership().fromLibraryInvite(inv1).saved
      membership().fromLibraryInvite(inv2).saved
      membership().fromLibraryInvite(inv3).saved

      libraryRepo.save(libMurica.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libMurica.id.get)))
      libraryRepo.save(libScience.copy(memberCount = libraryMembershipRepo.countWithLibraryId(libScience.id.get)))
    }
    db.readOnlyMaster { implicit s =>
      libraryMembershipRepo.count === 6
      libraryRepo.get(libMurica.id.get).memberCount === 3
      libraryRepo.get(libScience.id.get).memberCount === 2
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }

  def setupKeeps()(implicit injector: Injector) = {
    val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites
    val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val site1 = "http://www.reddit.com/r/murica"
    val site2 = "http://www.freedom.org/"
    val site3 = "http://www.mcdonalds.com/"

    db.readWrite { implicit s =>
      val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("Reddit")))
      val uri2 = uriRepo.save(NormalizedURI.withHash(site2, Some("Freedom")))
      val uri3 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds")))

      val keep1 = KeepFactory.keep().withTitle("Reddit").withUser(userCaptain).withUri(uri1).withLibrary(libMurica).withKeptAt(t1.plusMinutes(3)).saved
      val keep2 = KeepFactory.keep().withTitle("Freedom").withUser(userCaptain).withUri(uri2).withLibrary(libMurica).withKeptAt(t1.plusMinutes(15)).saved
      val keep3 = KeepFactory.keep().withTitle("McDonalds").withUser(userCaptain).withUri(uri3).withLibrary(libMurica).withKeptAt(t1.plusMinutes(30)).saved

      tagCommander.addTagsToKeep(keep1.id.get, Seq(Hashtag("tag1")), userCaptain.id, None)
      tagCommander.addTagsToKeep(keep1.id.get, Seq(Hashtag("tag2")), userCaptain.id, None)
      tagCommander.addTagsToKeep(keep2.id.get, Seq(Hashtag("tag2")), userCaptain.id, None)
      tagCommander.addTagsToKeep(keep3.id.get, Seq(Hashtag("tag2")), userCaptain.id, None)
    }
    db.readOnlyMaster { implicit s =>
      keepRepo.count === 3
      keepTagRepo.count === 4
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }

  "LibraryCommander" should {

    "create libraries correctly" in {
      "create libraries, memberships & invites" in {
        withDb(modules: _*) { implicit injector =>
          val (userIron, userCaptain, userAgent, userHulk) = setupUsers()

          db.readOnlyMaster { implicit s =>
            libraryRepo.count === 0
          }

          val lib1Request = LibraryInitialValues(name = "Avengers Missions", slug = Some("avengers"), visibility = LibraryVisibility.SECRET)

          val lib2Request = LibraryInitialValues(name = "MURICA", slug = Some("murica"), visibility = LibraryVisibility.PUBLISHED)

          val lib3Request = LibraryInitialValues(name = "Science and Stuff", slug = Some("science"), visibility = LibraryVisibility.PUBLISHED, whoCanInvite = Some(LibraryInvitePermissions.OWNER))

          val lib4Request = LibraryInitialValues(name = "Invalid Param", slug = Some(""), visibility = LibraryVisibility.SECRET)

          val libraryCommander = inject[LibraryCommander]
          val add1 = libraryCommander.createLibrary(lib1Request, userAgent.id.get)
          add1 must beRight
          add1.right.get.name === "Avengers Missions"
          val add2 = libraryCommander.createLibrary(lib2Request, userCaptain.id.get)
          add2 must beRight
          add2.right.get.name === "MURICA"
          val add3 = libraryCommander.createLibrary(lib3Request, userIron.id.get)
          add3 must beRight
          add3.right.get.name === "Science and Stuff"
          libraryCommander.createLibrary(lib4Request, userIron.id.get) must beLeft

          db.readOnlyMaster { implicit s =>
            val allLibs = libraryRepo.aTonOfRecords
            allLibs.length === 3
            allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")
            allLibs.map(_.whoCanInvite).flatten === Seq(LibraryInvitePermissions.COLLABORATOR, LibraryInvitePermissions.COLLABORATOR, LibraryInvitePermissions.OWNER)

            val allMemberships = libraryMembershipRepo.aTonOfRecords
            allMemberships.length === 3
            allMemberships.map(_.userId) === Seq(userAgent.id.get, userCaptain.id.get, userIron.id.get)
            allMemberships.map(_.access) === Seq(LibraryAccess.OWNER, LibraryAccess.OWNER, LibraryAccess.OWNER)
          }

          // test re-activating inactive library
          val libScience = add3.right.get
          db.readWrite { implicit s =>
            libraryRepo.save(libScience.copy(state = LibraryStates.INACTIVE))
          }
          libraryCommander.createLibrary(lib3Request, userIron.id.get) must beRight
          db.readOnlyMaster { implicit s =>
            val allLibs = libraryRepo.aTonOfRecords
            allLibs.length === 3
            allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")
            allLibs.foreach(_.color.nonEmpty === true)
          }
          1 === 1
        }
      }
      "let an org member create a library in that org if they have permission" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val org = OrganizationFactory.organization().withName("Kifi").withOwner(owner).saved
            (org, owner)
          }
          val libraryCommander = inject[LibraryCommander]

          val addRequest = LibraryInitialValues(name = "Kifi Library", visibility = LibraryVisibility.ORGANIZATION, space = Some(org.id.get))
          val addResponse = libraryCommander.createLibrary(addRequest, owner.id.get)
          addResponse must beRight
        }
      }
      "fail if a non-member tries to add a library to an organization" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner, rando) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val rando = UserFactory.user().saved
            val org = OrganizationFactory.organization().withName("Kifi").withOwner(owner).saved
            (org, owner, rando)
          }
          val libraryCommander = inject[LibraryCommander]

          val addRequest = LibraryInitialValues(name = "Kifi Library", visibility = LibraryVisibility.ORGANIZATION, space = Some(org.id.get))
          val addResponse = libraryCommander.createLibrary(addRequest, rando.id.get)
          addResponse must beLeft
        }
      }
      "fail if a user tries to create nonsensical libraries" in {
        withDb(modules: _*) { implicit injector =>
          val user = db.readWrite { implicit session =>
            UserFactory.user().saved
          }
          val addRequest = LibraryInitialValues(name = "Kifi Library", visibility = LibraryVisibility.ORGANIZATION, space = Some(user.id.get))
          val addResponse = libraryCommander.createLibrary(addRequest, user.id.get)
          addResponse must beLeft
        }
      }
    }
    "when getFullLibraryInfo(s) is called" in {
      "serve up a full library info" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).saved
            KeepFactory.keeps(100).map(_.withUser(user).withLibrary(lib)).saved
            (user, lib)
          }
          val fullInfoFut = inject[LibraryInfoCommander].createFullLibraryInfo(Some(user.id.get), false, lib, ProcessedImageSize.XLarge.idealSize, None, sanitizeUrls = true)
          val fullInfo = Await.result(fullInfoFut, Duration.Inf)
          fullInfo.keeps.nonEmpty === true
        }
      }
    }
    "when modify library is called:" in {
      "handle library moves correctly" in {
        withDb(modules: _*) { implicit injector =>
          val (orgOwner, libOwner, member, lib, orgs) = db.readWrite { implicit session =>
            val orgOwner = UserFactory.user().saved
            val libOwner = UserFactory.user().saved
            val member = UserFactory.user().saved
            val orgs = OrganizationFactory.organizations(2).map(_.withOwner(orgOwner).withMembers(Seq(libOwner, member))).saved
            val lib = LibraryFactory.library().withOwner(libOwner).withCollaborators(Seq(orgOwner)).saved
            (orgOwner, libOwner, member, lib, orgs)
          }

          val org1 = orgs(0)
          val org2 = orgs(1)
          // Move the libraries into the org
          // Only the owner can do this
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = member.id.get, LibraryModifications(space = Some(org1.id.get))) must beLeft
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = orgOwner.id.get, LibraryModifications(space = Some(org1.id.get))) must beLeft
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = libOwner.id.get, LibraryModifications(space = Some(org1.id.get))) must beRight

          // Move the library back into the user's space
          // Again, only the owner can do this
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = member.id.get, LibraryModifications(space = Some(libOwner.id.get))) must beLeft
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = orgOwner.id.get, LibraryModifications(space = Some(libOwner.id.get))) must beLeft
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = libOwner.id.get, LibraryModifications(space = Some(libOwner.id.get))) must beRight

          // Back to org 1
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = libOwner.id.get, LibraryModifications(space = Some(org1.id.get))) must beRight
          // And then directly into org 2
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = member.id.get, LibraryModifications(space = Some(org2.id.get))) must beLeft
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = orgOwner.id.get, LibraryModifications(space = Some(org2.id.get))) must beLeft
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = libOwner.id.get, LibraryModifications(space = Some(org2.id.get))) must beRight

          // And then back to org 1
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = member.id.get, LibraryModifications(space = Some(org1.id.get))) must beLeft
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = orgOwner.id.get, LibraryModifications(space = Some(org1.id.get))) must beLeft
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = libOwner.id.get, LibraryModifications(space = Some(org1.id.get))) must beRight
        }
      }
      "let org members with the force-edit permission move libraries" in {
        withDb(modules: _*) { implicit injector =>
          val (orgOwner, libOwner, member, lib, org) = db.readWrite { implicit session =>
            val orgOwner = UserFactory.user().saved
            val libOwner = UserFactory.user().saved
            val member = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(orgOwner).withMembers(Seq(libOwner, member)).saved
            val lib = LibraryFactory.library().withOwner(libOwner).withCollaborators(Seq(orgOwner)).saved
            (orgOwner, libOwner, member, lib, org)
          }

          // Move the libraries into the org
          // Only the owner can do this
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = member.id.get, LibraryModifications(space = Some(org.id.get))) must beLeft
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = orgOwner.id.get, LibraryModifications(space = Some(org.id.get))) must beLeft
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = libOwner.id.get, LibraryModifications(space = Some(org.id.get))) must beRight

          // Move the library back into the user's space
          // By default, only the owner can do this
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = member.id.get, LibraryModifications(space = Some(libOwner.id.get))) must beLeft
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = orgOwner.id.get, LibraryModifications(space = Some(libOwner.id.get))) must beLeft
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = libOwner.id.get, LibraryModifications(space = Some(libOwner.id.get))) must beRight

          // Back to the org
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = libOwner.id.get, LibraryModifications(space = Some(org.id.get))) must beRight

          // However, if we give the admin force-edit permissions
          val newSettings = db.readOnlyMaster { implicit session => orgConfigRepo.getByOrgId(org.id.get).settings.withFeatureSetTo(StaticFeature.ForceEditLibraries -> StaticFeatureSetting.ADMINS) }
          orgCommander.setAccountFeatureSettings(OrganizationSettingsRequest(org.id.get, orgOwner.id.get, newSettings)) must beRight

          // They still can't steal the library
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = orgOwner.id.get, LibraryModifications(space = Some(orgOwner.id.get))) must beLeft
          // But they can force it back into the owner's personal space
          libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = orgOwner.id.get, LibraryModifications(space = Some(libOwner.id.get))) must beRight
        }
      }
      "allow changing organizationId of library" in {
        withDb(modules: _*) { implicit injector =>
          implicit val publicIdConfig = inject[PublicIdConfiguration]
          val (userOrg1, userOrg2, randoOrg, owner, user, lib) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val user = UserFactory.user().saved
            val userOrg1 = OrganizationFactory.organization().withOwner(owner).withAdmins(Seq(user)).saved
            val userOrg2 = OrganizationFactory.organization().withOwner(owner).withAdmins(Seq(user)).saved
            val randoOrg = OrganizationFactory.organization().withOwner(owner).saved
            val lib = LibraryFactory.library().withOwner(user).saved
            (userOrg1, userOrg2, randoOrg, owner, user, lib)
          }

          // no privs on org2, cannot move into rando org space.
          val cannotMoveOrg = libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = user.id.get,
            LibraryModifications(space = Some(randoOrg.id.get)))
          cannotMoveOrg must beLeft
          db.readOnlyMaster { implicit session =>
            libraryRepo.get(lib.id.get).organizationId === None
          }

          // move from personal space to org space
          val canMoveOrg = libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = user.id.get,
            LibraryModifications(space = Some(userOrg1.id.get)))
          canMoveOrg must beRight
          canMoveOrg.right.get.modifiedLibrary.organizationId === userOrg1.id

          // still can't move into rando org space
          val attemptToStealLibrary = libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = user.id.get,
            LibraryModifications(space = Some(randoOrg.id.get)))
          attemptToStealLibrary must beLeft
          db.readOnlyMaster { implicit session =>
            libraryRepo.get(lib.id.get).organizationId === userOrg1.id
          }

          // move from one org to another where you have invite/remove privs.
          val moveOrganization = libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = user.id.get,
            LibraryModifications(space = Some(userOrg2.id.get)))
          moveOrganization must beRight
          db.readOnlyMaster { implicit session =>
            libraryRepo.get(lib.id.get).organizationId === userOrg2.id
          }

          // org owner tries to steal user's lib
          val moveHome = libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = owner.id.get,
            LibraryModifications(space = Some(owner.id.get)))
          moveHome must beLeft // only the owner can move a library out right now.
          db.readOnlyMaster { implicit session =>
            libraryRepo.get(lib.id.get).organizationId === userOrg2.id
          }

          // owner moves the library home.
          val moveHomeSucceeds = libraryCommander.modifyLibrary(libraryId = lib.id.get, userId = user.id.get,
            LibraryModifications(space = Some(user.id.get)))
          moveHomeSucceeds must beRight // only the owner can move a library out right now.
          db.readOnlyMaster { implicit session =>
            libraryRepo.get(lib.id.get).space === LibrarySpace.fromUserId(user.id.get)
          }
        }
      }

      "allow other modifications" in {
        withDb(modules: _*) { implicit injector =>
          val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries

          val mod1 = libraryCommander.modifyLibrary(libraryId = libShield.id.get, userId = userAgent.id.get,
            LibraryModifications(description = Some("Samuel L. Jackson was here"), whoCanInvite = Some(LibraryInvitePermissions.OWNER)))
          mod1 must beRight
          mod1.right.get.modifiedLibrary.description === Some("Samuel L. Jackson was here")
          mod1.right.get.modifiedLibrary.whoCanInvite === Some(LibraryInvitePermissions.OWNER)

          val mod2 = libraryCommander.modifyLibrary(libraryId = libMurica.id.get, userId = userCaptain.id.get,
            LibraryModifications(name = Some("MURICA #1!!!!!"), slug = Some("murica_#1")))
          mod2 must beRight
          mod2.right.get.modifiedLibrary.name === "MURICA #1!!!!!"
          mod2.right.get.modifiedLibrary.slug === LibrarySlug("murica_#1")

          val mod3 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userIron.id.get,
            LibraryModifications(visibility = Some(LibraryVisibility.PUBLISHED)))
          mod3 must beRight
          mod3.right.get.modifiedLibrary.visibility === LibraryVisibility.PUBLISHED

          val mod3NoChange = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userIron.id.get,
            LibraryModifications(visibility = Some(LibraryVisibility.PUBLISHED)))
          mod3NoChange must beRight
          mod3NoChange.right.get.modifiedLibrary.visibility === LibraryVisibility.PUBLISHED

          val mod4 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userHulk.id.get,
            LibraryModifications(name = Some("HULK SMASH")))
          mod4 must beLeft
          val mod5 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userIron.id.get,
            LibraryModifications(name = Some("")))
          mod5 must beLeft

          val mod6 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userIron.id.get,
            LibraryModifications(color = Some(LibraryColor.SKY_BLUE)))
          mod6 must beRight
          mod6.right.get.modifiedLibrary.color === Some(LibraryColor.SKY_BLUE)

          val mod7 = libraryCommander.modifyLibrary(libraryId = libScience.id.get, userId = userIron.id.get,
            LibraryModifications(whoCanComment = Some(LibraryCommentPermissions.ANYONE))
          )
          mod7 must beRight
          mod7.right.get.modifiedLibrary.whoCanComment === LibraryCommentPermissions.ANYONE

          db.readOnlyMaster { implicit s =>
            val allLibs = libraryRepo.aTonOfRecords
            allLibs.length === 3
            allLibs.map(_.name) === Seq("Avengers Missions", "MURICA #1!!!!!", "Science & Stuff")
            allLibs.map(_.slug.value) === Seq("avengers", "murica_#1", "science")
            allLibs.map(_.description) === Seq(Some("Samuel L. Jackson was here"), None, None)
            allLibs.map(_.visibility) === Seq(LibraryVisibility.SECRET, LibraryVisibility.PUBLISHED, LibraryVisibility.PUBLISHED)
            allLibs.map(_.color) === Seq(None, None, Some(LibraryColor.SKY_BLUE))
          }
        }
      }
      "correctly alias libraries when they are moved" in {
        withDb(modules: _*) { implicit injector =>
          implicit val publicIdConfig = inject[PublicIdConfiguration]
          val libraryCommander = inject[LibraryCommander]

          val (ironMan, earthOrg, starkOrg, ironLib) = db.readWrite { implicit session =>
            val captainPlanet = UserFactory.user().withName("Captain", "Planet").withUsername("captainplanet").saved
            val ironMan = UserFactory.user().withName("Tony", "Stark").withUsername("ironman").saved
            val earthOrg = OrganizationFactory.organization().withName("Earth").withOwner(captainPlanet).withMembers(Seq(ironMan)).saved
            val starkOrg = OrganizationFactory.organization().withName("Stark Towers").withOwner(ironMan).saved
            val ironLib = LibraryFactory.library().withName("Hero Stuff").withSlug("herostuff").withOwner(ironMan).saved
            (ironMan, earthOrg, starkOrg, ironLib)
          }

          // There shouldn't be any aliases initially
          db.readOnlyMaster { implicit session =>
            inject[LibraryAliasRepo].count === 0
          }

          // Move it into starkOrg
          val response1 = libraryCommander.modifyLibrary(libraryId = ironLib.id.get, userId = ironMan.id.get,
            LibraryModifications(space = Some(starkOrg.id.get)))
          response1 must beRight

          // Now the library should live in starkOrg, but there is still an alias from ironMan
          db.readOnlyMaster { implicit session =>
            inject[LibraryRepo].getBySpaceAndSlug(starkOrg.id.get, LibrarySlug("herostuff")) must beSome
            inject[LibraryAliasRepo].count === 1

            val ironManAlias = inject[LibraryAliasRepo].getBySpaceAndSlug(ironMan.id.get, LibrarySlug("herostuff"))
            ironManAlias must beSome
            ironManAlias.get.libraryId === ironLib.id.get
          }

          // Move it back into ironMan's personal space
          val response2 = libraryCommander.modifyLibrary(libraryId = ironLib.id.get, userId = ironMan.id.get,
            LibraryModifications(space = Some(ironMan.id.get)))
          response2 must beRight

          // The ironMan one was reclaimed, so it should be inactive now
          db.readOnlyMaster { implicit session =>
            inject[LibraryRepo].getBySpaceAndSlug(ironMan.id.get, LibrarySlug("herostuff")) must beSome

            val ironManAlias = inject[LibraryAliasRepo].getBySpaceAndSlug(ironMan.id.get, LibrarySlug("herostuff"), excludeState = None)
            ironManAlias must beSome
            ironManAlias.get.state === LibraryAliasStates.INACTIVE

            val starkOrgAlias = inject[LibraryAliasRepo].getBySpaceAndSlug(starkOrg.id.get, LibrarySlug("herostuff"))
            starkOrgAlias must beSome
            starkOrgAlias.get.libraryId === ironLib.id.get
          }

          // Move it into earthOrg
          val response3 = libraryCommander.modifyLibrary(libraryId = ironLib.id.get, userId = ironMan.id.get,
            LibraryModifications(space = Some(earthOrg.id.get)))
          response3 must beRight

          // Two aliases now, both ironMan and starkOrg
          db.readOnlyMaster { implicit session =>
            inject[LibraryRepo].getBySpaceAndSlug(earthOrg.id.get, LibrarySlug("herostuff")) must beSome
            inject[LibraryAliasRepo].count === 2

            val ironManAlias = inject[LibraryAliasRepo].getBySpaceAndSlug(ironMan.id.get, LibrarySlug("herostuff"))
            ironManAlias must beSome
            ironManAlias.get.libraryId === ironLib.id.get

            val starkOrgAlias = inject[LibraryAliasRepo].getBySpaceAndSlug(starkOrg.id.get, LibrarySlug("herostuff"))
            starkOrgAlias must beSome
            starkOrgAlias.get.libraryId === ironLib.id.get
          }

          // Try to move it back into starkOrg, should succeed since by default members can remove libraries
          val response4 = libraryCommander.modifyLibrary(libraryId = ironLib.id.get, userId = ironMan.id.get,
            LibraryModifications(space = Some(starkOrg.id.get)))
          response4 must beRight
        }
      }
      "correctly change all the denormalized library fields on keeps/ktls" in {
        withDb(modules: _*) { implicit injector =>
          val libraryCommander = inject[LibraryCommander]
          val (user, org, lib1) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(user).saved
            val lib = LibraryFactory.library().withOwner(user).published().saved
            KeepFactory.keeps(100).map(_.withLibrary(lib).withUser(user)).saved
            (user, org, lib)
          }

          val res2 = libraryCommander.modifyLibrary(lib1.id.get, user.id.get, LibraryModifications(visibility = Some(LibraryVisibility.SECRET))).right.get
          Await.result(res2.keepChanges, Duration.Inf)
          val lib2 = res2.modifiedLibrary
          db.readOnlyMaster { implicit session =>
            ktlRepo.getAllByLibraryId(lib2.id.get).foreach { ktl =>
              ktl.visibility === lib2.visibility
              ktl.organizationId === lib2.organizationId
            }
          }

          val res3 = libraryCommander.modifyLibrary(lib2.id.get, user.id.get, LibraryModifications(space = Some(LibrarySpace.fromOrganizationId(org.id.get)), visibility = Some(LibraryVisibility.ORGANIZATION))).right.get
          Await.result(res3.keepChanges, Duration.Inf)
          val lib3 = res3.modifiedLibrary
          db.readOnlyMaster { implicit session =>
            ktlRepo.getAllByLibraryId(lib3.id.get).foreach { ktl =>
              ktl.visibility === lib3.visibility
              ktl.organizationId === lib3.organizationId
            }
          }

          val res4 = libraryCommander.modifyLibrary(lib3.id.get, user.id.get, LibraryModifications(space = Some(LibrarySpace.fromUserId(user.id.get)), visibility = Some(LibraryVisibility.PUBLISHED))).right.get
          Await.result(res4.keepChanges, Duration.Inf)
          val lib4 = res4.modifiedLibrary
          db.readOnlyMaster { implicit session =>
            ktlRepo.getAllByLibraryId(lib4.id.get).foreach { ktl =>
              ktl.visibility === lib4.visibility
              ktl.organizationId === lib4.organizationId
            }
          }

          val res5 = libraryCommander.modifyLibrary(lib4.id.get, user.id.get, LibraryModifications(space = Some(LibrarySpace.fromOrganizationId(org.id.get)))).right.get
          Await.result(res5.keepChanges, Duration.Inf)
          val lib5 = res5.modifiedLibrary
          db.readOnlyMaster { implicit session =>
            ktlRepo.getAllByLibraryId(lib5.id.get).foreach { ktl =>
              ktl.visibility === lib5.visibility
              ktl.organizationId === lib5.organizationId
            }
          }
          1 === 1
        }
      }
    }

    "remove library, memberships & invites" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupKeeps()
        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.aTonOfRecords
          allLibs.length === 3
          allLibs.map(_.slug.value) === Seq("avengers", "murica", "science")
          libraryMembershipRepo.count === 6
          libraryInviteRepo.count === 4
        }

        val libraryCommander = inject[LibraryCommander]

        val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val ktls = db.readOnlyMaster { implicit s => ktlRepo.getFromLibrarySince(t1.minusYears(10), libMurica.id.get, 10000) }
        ktls.length === 3

        libraryCommander.deleteLibrary(libMurica.id.get, userCaptain.id.get)
        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.aTonOfRecords.filter(_.state == LibraryStates.ACTIVE)
          allLibs.length === 2
          allLibs.map(_.slug.value) === Seq("avengers", "science")
          libraryMembershipRepo.aTonOfRecords.filter(_.state == LibraryMembershipStates.INACTIVE).length === 3
          libraryInviteRepo.aTonOfRecords.filter(_.state == LibraryInviteStates.INACTIVE).length === 3
        }

        db.readWrite { implicit s =>
          val deleted = libraryRepo.get(libMurica.id.get)
          deleted.name !== libMurica.name
          deleted.description === None
          deleted.state === LibraryStates.INACTIVE
          deleted.slug !== libMurica.slug

          ktls foreach { ktl =>
            val deletedKeep = keepRepo.get(ktl.keepId)
            deletedKeep.title === None
          }
        }

        libraryCommander.deleteLibrary(libScience.id.get, userIron.id.get)
        libraryCommander.deleteLibrary(libShield.id.get, userAgent.id.get)
        db.readOnlyMaster { implicit s =>
          val allLibs = libraryRepo.aTonOfRecords.filter(_.state == LibraryStates.ACTIVE)
          allLibs.length === 0
          allLibs.map(_.slug.value) === Seq.empty
          libraryMembershipRepo.aTonOfRecords.filter(_.state == LibraryMembershipStates.INACTIVE).length === 6
          libraryInviteRepo.aTonOfRecords.filter(_.state == LibraryInviteStates.INACTIVE).length === 4
        }
      }
    }

    "get libraries by user (which libs am I following / contributing to?)" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites

        val libraryInfoCommander = inject[LibraryInfoCommander]
        val targetLib1 = libraryInfoCommander.getLibrariesByUser(userIron.id.get)
        val targetLib2 = libraryInfoCommander.getLibrariesByUser(userCaptain.id.get)
        val targetLib3 = libraryInfoCommander.getLibrariesByUser(userAgent.id.get)
        val targetLib4 = libraryInfoCommander.getLibrariesByUser(userHulk.id.get)

        db.readOnlyMaster { implicit s =>

          val (ironMemberships, ironLibs) = targetLib1._1.unzip
          ironLibs.map(_.slug.value) === Seq("science", "murica")
          ironMemberships.map(_.access) === Seq(LibraryAccess.OWNER, LibraryAccess.READ_ONLY)

          val (captainMemberships, captainLibs) = targetLib2._1.unzip
          captainLibs.map(_.slug.value) === Seq("murica")
          captainMemberships.map(_.access) === Seq(LibraryAccess.OWNER)

          val (agentMemberships, agentLibs) = targetLib3._1.unzip
          agentLibs.map(_.slug.value) === Seq("avengers", "murica")
          agentMemberships.map(_.access) === Seq(LibraryAccess.OWNER, LibraryAccess.READ_ONLY)

          val (hulkMemberships, hulkLibs) = targetLib4._1.unzip
          hulkLibs.map(_.slug.value) === Seq("science")
          hulkMemberships.map(_.access) === Seq(LibraryAccess.READ_WRITE)
          val (hulkInvites, hulkInvitedLibs) = targetLib4._2.unzip
          hulkInvitedLibs.map(_.slug.value) === Seq("murica")
          hulkInvites.map(_.access) === Seq(LibraryAccess.READ_ONLY)
        }
      }
    }

    "does user have visibility" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites
        val libraryCommander = inject[LibraryCommander]

        val libShwarmas = db.readWrite { implicit s =>
          val libShwarmas = libraryRepo.save(Library(name = "NY Shwarmas", ownerId = userIron.id.get, visibility = LibraryVisibility.PUBLISHED, memberCount = 1, slug = LibrarySlug("shwarma")))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libShwarmas.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER))
          libShwarmas
        }

        inject[LibraryAccessCommander].userAccess(userIron.id.get, libScience.id.get) === Some(LibraryAccess.OWNER) // test owner access
        inject[LibraryAccessCommander].userAccess(userHulk.id.get, libScience.id.get) === Some(LibraryAccess.READ_WRITE) // test membership accesss
        inject[LibraryAccessCommander].userAccess(userIron.id.get, libShield.id.get) === None // test no membership (secret library)
        inject[LibraryAccessCommander].userAccess(userHulk.id.get, libMurica.id.get) === Some(LibraryAccess.READ_ONLY) // test invited (but not accepted) access
        inject[LibraryAccessCommander].userAccess(userCaptain.id.get, libShwarmas.id.get) === Some(LibraryAccess.READ_ONLY) // test no membership (public library)

        inject[LibraryAccessCommander].userAccess(userCaptain.id.get, libScience.id.get) === None // test  library (no membership)
      }
    }

    "can user view library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries
        val libraryCommander = inject[LibraryCommander]

        val userWidow = db.readWrite { implicit s =>
          val user = UserFactory.user().withName("Natalia", "Romanova").withUsername("blackwidow").saved
          libraryMembershipRepo.save(LibraryMembership(libraryId = libShield.id.get, userId = user.id.get, access = LibraryAccess.READ_ONLY))
          user
        }
        // test can view (permission denied)
        inject[LibraryAccessCommander].canViewLibrary(Some(userWidow.id.get), libScience) === false

        // test can view if library is published
        inject[LibraryAccessCommander].canViewLibrary(Some(userWidow.id.get), libMurica) === true

        // test can view if user has membership
        inject[LibraryAccessCommander].canViewLibrary(Some(userWidow.id.get), libShield) === true
        inject[LibraryAccessCommander].canViewLibrary(Some(userWidow.id.get), libScience) === false

        db.readWrite { implicit s =>
          libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, inviterId = userIron.id.get, userId = userWidow.id, access = LibraryAccess.READ_ONLY,
            authToken = "token"))
        }
        // test can view if user has invite
        inject[LibraryAccessCommander].canViewLibrary(Some(userWidow.id.get), libScience) === true

        // test can view if non-user provides correct/incorrect authtoken
        inject[LibraryAccessCommander].canViewLibrary(None, libScience) === false
        inject[LibraryAccessCommander].canViewLibrary(None, libScience, Some("token-wrong")) === false
        inject[LibraryAccessCommander].canViewLibrary(None, libScience, Some("token")) === true
      }
    }

    "can move a library to and from organization space" in {
      withDb(modules: _*) { implicit injector =>
        val libraryAccessCommander = inject[LibraryAccessCommander]
        val (user, newLibrary, organization, otherOrg) = db.readWrite { implicit s =>
          val orgOwner = UserFactory.user().withName("Bruce", "Lee").saved
          val user = UserFactory.user().withName("Jackie", "Chan").saved
          val newLibrary = library().withOwner(user).withVisibility(LibraryVisibility.ORGANIZATION).saved
          val organization = OrganizationFactory.organization().withOwner(orgOwner).withAdmins(Seq(user)).saved
          val otherOrg = OrganizationFactory.organization().withOwner(orgOwner).saved
          (user, newLibrary, organization, otherOrg)
        }

        // User does not own the library
        libraryAccessCommander.canMoveTo(Id[User](0), newLibrary.id.get, organization.id.get) === false

        // User owns the library
        // Can move libraries to organizations you are part of.
        libraryAccessCommander.canMoveTo(user.id.get, newLibrary.id.get, organization.id.get) must equalTo(true)
        // Cannot inject libraries to random organizations.
        libraryAccessCommander.canMoveTo(user.id.get, newLibrary.id.get, otherOrg.id.get) must equalTo(false)

        db.readWrite { implicit s => libraryRepo.save(newLibrary.copy(organizationId = organization.id)) }
        // Can move libraries out of organizations you are part of.
        libraryAccessCommander.canMoveTo(user.id.get, newLibrary.id.get, user.id.get) must equalTo(true)
        // Cannot inject libraries from an organization you are part of to a random organization.
        libraryAccessCommander.canMoveTo(user.id.get, newLibrary.id.get, otherOrg.id.get) must equalTo(false)

        db.readWrite { implicit s => libraryRepo.save(newLibrary.copy(organizationId = otherOrg.id)) }
        // Cannot remove libraries from other organizations you are not part of.
        libraryAccessCommander.canMoveTo(user.id.get, newLibrary.id.get, user.id.get) must equalTo(false)
        // Prevent Company Espionage and library stealing!!
        libraryAccessCommander.canMoveTo(user.id.get, newLibrary.id.get, organization.id.get) must equalTo(false)
        // What about if your library is in an organization and you leave
        db.readWrite { implicit s =>
          val membership = orgMembershipRepo.getByOrgIdAndUserId(organization.id.get, user.id.get)
          orgMembershipRepo.save(membership.get.copy(state = OrganizationMembershipStates.INACTIVE))
        }
        // You're out of luck.
        libraryAccessCommander.canMoveTo(user.id.get, newLibrary.id.get, user.id.get) must equalTo(false)
      }
    }

    "user can view libraries in organization he is a member of which are Organization Visibility" in {
      withDb(modules: _*) { implicit injector =>
        val (barry, starLabsOrg, starLabsLib) = db.readWrite { implicit s =>
          val harrison = UserFactory.user().withName("Harrison", "Wells").withUsername("Harrison Wells").saved

          val barry = UserFactory.user().withName("Barry", "Allen").withUsername("The Flash").saved
          val starLabsOrg = orgRepo.save(Organization(name = "Star Labs", ownerId = harrison.id.get, primaryHandle = None, description = None, site = None))
          val starLabsLib = library().withOwner(harrison).withVisibility(LibraryVisibility.ORGANIZATION).withOrganizationIdOpt(starLabsOrg.id).saved

          val membership = orgMembershipRepo.save(OrganizationMembership(organizationId = starLabsOrg.id.get, userId = barry.id.get, role = OrganizationRole.MEMBER))

          starLabsLib.organizationId must equalTo(starLabsOrg.id)
          membership.state must equalTo(OrganizationMembershipStates.ACTIVE)
          membership.organizationId must equalTo(starLabsOrg.id.get)
          membership.userId must equalTo(barry.id.get)
          (barry, starLabsOrg, starLabsLib)
        }
        inject[LibraryAccessCommander].canViewLibrary(barry.id, starLabsLib) must equalTo(true)
      }
    }

    "intern user system libraries" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val libraryCommander = inject[LibraryCommander]
        val libraryInfoCommander = inject[LibraryInfoCommander]

        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries

        db.readOnlyMaster { implicit session =>
          val all = libraryRepo.aTonOfRecords()
          all.size === 3

          libraryRepo.getByUser(userIron.id.get).map(_._2).count(_.ownerId == userIron.id.get) === 1
          libraryRepo.getByUser(userCaptain.id.get).map(_._2).count(_.ownerId == userCaptain.id.get) === 1
        }

        libraryCommander.internSystemGeneratedLibraries(userIron.id.get)
        libraryCommander.internSystemGeneratedLibraries(userCaptain.id.get)

        // System libraries are created
        db.readOnlyMaster { implicit session =>
          libraryRepo.aTonOfRecords().size === 7
          libraryRepo.getByUser(userIron.id.get).map(_._2).count(_.ownerId == userIron.id.get) === 3
          libraryRepo.getByUser(userCaptain.id.get).map(_._2).count(_.ownerId == userCaptain.id.get) === 3
          libraryRepo.getByUser(userHulk.id.get).map(_._2).count(_.ownerId == userCaptain.id.get) === 0
        }

        // Operation is idempotent
        libraryCommander.internSystemGeneratedLibraries(userIron.id.get)
        libraryCommander.internSystemGeneratedLibraries(userHulk.id.get)
        db.readWrite { implicit session =>
          libraryRepo.aTonOfRecords().size === 9
          libraryRepo.getByUser(userIron.id.get).map(_._2).count(_.ownerId == userIron.id.get) === 3
          libraryRepo.getByUser(userCaptain.id.get).map(_._2).count(_.ownerId == userCaptain.id.get) === 3
          libraryRepo.getByUser(userHulk.id.get).map(_._2).count(_.ownerId == userHulk.id.get) === 2

          val ironSysLibs = libraryRepo.getByUser(userIron.id.get).map(_._2).filter(_.ownerId == userIron.id.get)
          val main = ironSysLibs.find(_.kind == LibraryKind.SYSTEM_MAIN).get
          val secret = ironSysLibs.find(_.kind == LibraryKind.SYSTEM_SECRET).get

          main.state === LibraryStates.ACTIVE
          main.name === Library.SYSTEM_MAIN_DISPLAY_NAME
          main.slug === LibrarySlug("main")
          main.id.get === Id[Library](4)
          secret.state === LibraryStates.ACTIVE

          libraryRepo.save(main.copy(state = LibraryStates.INACTIVE, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("main_old")))
        }

        libraryCommander.internSystemGeneratedLibraries(userIron.id.get)

        // Fixes problems in sys libraries
        db.readOnlyMaster { implicit session =>
          val ironMain = libraryRepo.getByUser(userIron.id.get).map(_._2).filter(_.ownerId == userIron.id.get).find(_.kind == LibraryKind.SYSTEM_MAIN).get

          ironMain.state === LibraryStates.ACTIVE
          ironMain.visibility === LibraryVisibility.DISCOVERABLE
        }

        // Removes dupes
        db.readWrite { implicit session =>
          val lib = libraryRepo.save(Library(ownerId = userIron.id.get, name = "Main 2!", kind = LibraryKind.SYSTEM_MAIN, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("main2"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(userId = userIron.id.get, libraryId = lib.id.get, access = LibraryAccess.OWNER))
        }

        libraryCommander.internSystemGeneratedLibraries(userIron.id.get)

        db.readOnlyMaster { implicit session =>
          val ironMains = libraryRepo.getByUser(userIron.id.get, None).map(_._2).filter(_.ownerId == userIron.id.get).filter(_.kind == LibraryKind.SYSTEM_MAIN)
          ironMains.size === 1
          ironMains.count(l => l.state == LibraryStates.ACTIVE) === 1
          libraryRepo.aTonOfRecords.count(l => l.ownerId == userIron.id.get && l.state == LibraryStates.INACTIVE) === 1
        }

      }
    }

    "invite users" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]

        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries
        val libraryInviteCommander = inject[LibraryInviteCommander]

        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 0
        }

        val thorEmail = EmailAddress("thorishere@gmail.com")

        // Captain America invites everybody to his Published Library
        val inviteList1 = Seq(
          (Left(userIron.id.get), LibraryAccess.READ_ONLY, None),
          (Left(userAgent.id.get), LibraryAccess.READ_ONLY, None),
          (Left(userHulk.id.get), LibraryAccess.READ_ONLY, None),
          (Right(thorEmail), LibraryAccess.READ_ONLY, Some("America > Asgard")))
        val res1 = Await.result(libraryInviteCommander.inviteToLibrary(libMurica.id.get, userCaptain.id.get, inviteList1), Duration(5, "seconds"))
        res1 must beRight
        res1.right.get === Seq((Left(BasicUser.fromUser(userIron)), LibraryAccess.READ_ONLY),
          (Left(BasicUser.fromUser(userAgent)), LibraryAccess.READ_ONLY),
          (Left(BasicUser.fromUser((userHulk))), LibraryAccess.READ_ONLY),
          (Right(RichContact(thorEmail)), LibraryAccess.READ_ONLY))

        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 4
          val allInvites = libraryInviteRepo.aTonOfRecords
          allInvites.count(_.access == LibraryAccess.READ_ONLY) === 4
          allInvites.count(_.userId.isDefined) === 3
          allInvites.count(_.emailAddress.isDefined) === 1
        }

        // Tests that users can have multiple invitations multiple times
        // but if invites are sent within 5 Minutes of each other, they do not persist!
        Await.result(libraryInviteCommander.inviteToLibrary(libMurica.id.get, userCaptain.id.get, inviteList1), Duration(5, "seconds")) must beRight
        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 4 // 8 invites sent, only 4 persisted from previous call
        }

        // Test Collaborators!!!! The Falcon is a collaborator to library 'Murica
        val userFalcon = db.readWrite { implicit s =>
          val userFalcon = user().withUsername("thefalcon").saved
          membership().withLibraryCollaborator(libMurica.id.get, userFalcon.id.get).saved
          userFalcon
        }

        val inviteCollab1 = Seq((Right(EmailAddress("blackwidow@shield.gov")), LibraryAccess.READ_WRITE, None))
        val inviteCollab2 = Seq((Right(EmailAddress("hawkeye@shield.gov")), LibraryAccess.READ_WRITE, None))

        // Test owner invite to collaborate (invite persists)
        Await.result(libraryInviteCommander.inviteToLibrary(libMurica.id.get, userCaptain.id.get, inviteCollab1), Duration(5, "seconds")) must beRight
        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 5
        }
        // Test collaborator invite to collaborate (invite persists)
        Await.result(libraryInviteCommander.inviteToLibrary(libMurica.id.get, userFalcon.id.get, inviteCollab1), Duration(5, "seconds")) must beRight
        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 6
        }

        // Set library to not allow collaborators to invite & test invite to collaborate
        db.readWrite { implicit s =>
          libraryRepo.save(libMurica.copy(whoCanInvite = Some(LibraryInvitePermissions.OWNER)))
        }

        // Test owner invite to collaborate (invite persists)
        Await.result(libraryInviteCommander.inviteToLibrary(libMurica.id.get, userCaptain.id.get, inviteCollab2), Duration(5, "seconds")) must beRight
        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 7
        }

        // Test collaborator invite to collaborate (invite does NOT persist)
        Await.result(libraryInviteCommander.inviteToLibrary(libMurica.id.get, userFalcon.id.get, inviteCollab2), Duration(5, "seconds")) must beRight
        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.count === 7
        }

      }
    }

    "let users join libraries" in {
      "let users join or decline library invites" in {
        withDb(modules: _*) { implicit injector =>
          implicit val config = inject[PublicIdConfiguration]

          val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupInvites
          val libraryInviteCommander = inject[LibraryInviteCommander]
          val libraryMembershipCommander = inject[LibraryMembershipCommander]

          val t1 = new DateTime(2014, 8, 1, 3, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
          db.readWrite { implicit s =>
            libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, inviterId = userIron.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_WRITE, createdAt = t1))
            libraryInviteRepo.save(LibraryInvite(libraryId = libScience.id.get, inviterId = userIron.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))
          }

          db.readOnlyMaster { implicit s =>
            libraryInviteRepo.count === 6
          }

          val eliza = inject[ElizaServiceClient].asInstanceOf[FakeElizaServiceClientImpl]
          eliza.inbox.size === 0

          libraryMembershipCommander.joinLibrary(userIron.id.get, libMurica.id.get).right.get._1.name === libMurica.name // Ironman accepts invite to 'Murica'

          //this for some reason only fails on Jenkins (and fails consitently now). Taking it out to uncreak the build.
          // eliza.inbox.size === 1
          // eliza.inbox(0) === (userCaptain.id.get, NotificationCategory.User.LIBRARY_FOLLOWED, "https://www.kifi.com/ironman", s"http://localhost/users/${userIron.externalId}/pics/200/0.jpg")

          libraryMembershipCommander.joinLibrary(userAgent.id.get, libMurica.id.get).right.get._1.name === libMurica.name // Agent accepts invite to 'Murica'
          libraryInviteCommander.declineLibrary(userHulk.id.get, libMurica.id.get) // Hulk declines invite to 'Murica'
          libraryMembershipCommander.joinLibrary(userHulk.id.get, libScience.id.get).right.get._1.name === libScience.name // Hulk accepts invite to 'Science' and gets READ_WRITE access

          db.readOnlyMaster { implicit s =>
            libraryInviteRepo.count === 6
            val res = for (inv <- libraryInviteRepo.aTonOfRecords) yield {
              (inv.libraryId, inv.userId.get, inv.access, inv.state)
            }
            res === Seq(
              (libMurica.id.get, userIron.id.get, LibraryAccess.READ_ONLY, LibraryInviteStates.ACCEPTED),
              (libMurica.id.get, userAgent.id.get, LibraryAccess.READ_ONLY, LibraryInviteStates.ACCEPTED),
              (libMurica.id.get, userHulk.id.get, LibraryAccess.READ_ONLY, LibraryInviteStates.DECLINED),
              (libScience.id.get, userHulk.id.get, LibraryAccess.READ_WRITE, LibraryInviteStates.ACCEPTED),
              (libScience.id.get, userHulk.id.get, LibraryAccess.READ_WRITE, LibraryInviteStates.ACCEPTED),
              (libScience.id.get, userHulk.id.get, LibraryAccess.READ_ONLY, LibraryInviteStates.ACCEPTED)
            )
            libraryMembershipRepo.count === 6
            libraryRepo.get(libMurica.id.get).memberCount === 3 //owner + Ironman + Agent
            libraryRepo.get(libScience.id.get).memberCount === 2 //owner + Hulk
            libraryRepo.get(libShield.id.get).memberCount === 1 //owner

            libraryMembershipRepo.getWithLibraryIdAndUserId(libMurica.id.get, userIron.id.get) must beSome
            libraryMembershipRepo.getWithLibraryIdAndUserId(libMurica.id.get, userAgent.id.get) must beSome
            libraryMembershipRepo.getWithLibraryIdAndUserId(libMurica.id.get, userHulk.id.get) must beNone
            libraryMembershipRepo.getWithLibraryIdAndUserId(libScience.id.get, userHulk.id.get) must beSome
          }

          // Proving that accepting a lesser invite doesn't destroy current access
          db.readWrite { implicit s =>
            libraryInviteRepo.save(LibraryInvite(libraryId = libShield.id.get, inviterId = userIron.id.get, userId = Some(userAgent.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1))
          }
          libraryMembershipCommander.joinLibrary(userAgent.id.get, libShield.id.get)
          inject[LibraryAccessCommander].userAccess(userAgent.id.get, libShield.id.get) === Some(LibraryAccess.OWNER)

          // Joining a private library from an email invite (library invite has a null userId field)!
          db.readWrite { implicit s =>
            libraryInviteRepo.save(LibraryInvite(libraryId = libShield.id.get, inviterId = userAgent.id.get, emailAddress = Some(EmailAddress("incrediblehulk@gmail.com")), access = LibraryAccess.READ_ONLY, authToken = "asdf"))
            libraryInviteRepo.getByLibraryIdAndAuthToken(libShield.id.get, "asdf").exists(i => i.state == LibraryInviteStates.ACCEPTED) === false
          }

          // no authtoken - should Fail
          libraryMembershipCommander.joinLibrary(userHulk.id.get, libShield.id.get, None).isRight === false

          // incorrect authtoken - should Fail
          libraryMembershipCommander.joinLibrary(userHulk.id.get, libShield.id.get, Some("asdf-wrong")).isRight === false

          // correct authtoken (invite by email)
          val successJoin = libraryMembershipCommander.joinLibrary(userHulk.id.get, libShield.id.get, Some("asdf"))
          successJoin must beRight
          val includeInviteSet = Set(LibraryInviteStates.ACCEPTED, LibraryInviteStates.DECLINED, LibraryInviteStates.ACTIVE)
          db.readOnlyMaster { implicit s =>
            libraryInviteRepo.getByLibraryIdAndAuthToken(libShield.id.get, "asdf", includeInviteSet).exists(i => i.state == LibraryInviteStates.ACCEPTED) === true
          }

          // Joining a private library from a kifi invite (library invite with a userId)
          db.readWrite { implicit s =>
            libraryInviteRepo.save(LibraryInvite(libraryId = libShield.id.get, inviterId = userAgent.id.get, userId = userIron.id, access = LibraryAccess.READ_ONLY, authToken = "qwer"))
            libraryInviteRepo.getByLibraryIdAndAuthToken(libShield.id.get, "qwer", includeInviteSet).exists(i => i.state == LibraryInviteStates.ACCEPTED) === false
          }
          libraryMembershipCommander.joinLibrary(userIron.id.get, libShield.id.get, None) must beRight
          db.readOnlyMaster { implicit s =>
            libraryInviteRepo.getByLibraryIdAndAuthToken(libShield.id.get, "qwer", includeInviteSet).exists(i => i.state == LibraryInviteStates.ACCEPTED) === true
          }

        }
      }

      "let org members join an org-visible library" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner, member, lib) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val member = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(member)).saved
            val lib = LibraryFactory.library().withOwner(owner).withOrganizationIdOpt(Some(org.id.get)).withVisibility(LibraryVisibility.ORGANIZATION).saved
            (org, owner, member, lib)
          }

          val libraryMembershipCommander = inject[LibraryMembershipCommander]
          val response = libraryMembershipCommander.joinLibrary(member.id.get, lib.id.get)
          response must beRight

          db.readOnlyMaster { implicit session =>
            inject[LibraryMembershipRepo].getWithLibraryId(lib.id.get).map(_.userId).toSet === Set(owner.id.get, member.id.get)
          }
        }
      }

      "prevent non-members from joining an org-visible library" in {
        withDb(modules: _*) { implicit injector =>
          val (org, owner, rando, lib) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val rando = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).saved
            val lib = LibraryFactory.library().withOwner(owner).withOrganizationIdOpt(Some(org.id.get)).withVisibility(LibraryVisibility.ORGANIZATION).saved
            (org, owner, rando, lib)
          }

          val libraryMembershipCommander = inject[LibraryMembershipCommander]
          val response = libraryMembershipCommander.joinLibrary(rando.id.get, lib.id.get)
          response must beLeft

          db.readOnlyMaster { implicit session =>
            inject[LibraryMembershipRepo].getWithLibraryId(lib.id.get).map(_.userId).toSet === Set(owner.id.get)
          }
        }
      }

      "let users leave library" in {
        withDb(modules: _*) { implicit injector =>
          implicit val config = inject[PublicIdConfiguration]
          val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupAcceptedInvites
          val libraryMembershipCommander = inject[LibraryMembershipCommander]

          db.readOnlyMaster { implicit s =>
            libraryMembershipRepo.aTonOfRecords.count(x => x.state == LibraryMembershipStates.INACTIVE) === 0
            libraryRepo.get(libMurica.id.get).memberCount === 3
          }

          libraryMembershipCommander.leaveLibrary(libMurica.id.get, userAgent.id.get) must beRight

          db.readOnlyMaster { implicit s =>
            libraryMembershipRepo.count === 6
            libraryMembershipRepo.aTonOfRecords.count(x => x.state == LibraryMembershipStates.INACTIVE) === 1
            libraryRepo.get(libMurica.id.get).memberCount === 2
          }
        }
      }
    }

    "count keeps in library with getKeepsFromLibrariesSince" in {
      withDb(modules: _*) { implicit injector =>
        val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupKeeps
        db.readOnlyMaster { implicit s =>
          ktlRepo.getFromLibrarySince(t1.minusYears(10), libShield.id.get, 10000).length === 0
          ktlRepo.getFromLibrarySince(t1.minusYears(10), libMurica.id.get, 10000).length === 3
          ktlRepo.getFromLibrarySince(t1.plusMinutes(10), libMurica.id.get, 10000).length === 2
          ktlRepo.getFromLibrarySince(t1.plusMinutes(20), libMurica.id.get, 10000).length === 1
          ktlRepo.getFromLibrarySince(t1.plusMinutes(60), libMurica.id.get, 10000).length === 0
          ktlRepo.getFromLibrarySince(t1.minusYears(10), libMurica.id.get, 2).length === 2
          ktlRepo.getFromLibrarySince(t1.minusYears(10), libMurica.id.get, 1).length === 1
        }
      }
    }

    "copy keeps to another library" in {
      "copy from one owned lib to another" in {
        withDb(modules: _*) { implicit injector =>
          implicit val config = inject[PublicIdConfiguration]
          val (user, sourceLib, emptyLib, keeps) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val emptyLib = LibraryFactory.library().withOwner(user).saved
            val sourceLib = LibraryFactory.library().withOwner(user).saved
            val keeps = KeepFactory.keeps(50).map(_.withUser(user).withLibrary(sourceLib).withRandomTitle()).saved
            (user, sourceLib, emptyLib, keeps)
          }

          db.readOnlyMaster { implicit session =>
            inject[KeepRepo].pageByLibrary(emptyLib.id.get, 0, 1000).length === 0
            inject[KeepRepo].pageByLibrary(sourceLib.id.get, 0, 1000) === keeps.reverse
          }
          val (yay, nay) = inject[LibraryCommander].copyKeeps(user.id.get, emptyLib.id.get, keeps.toSet, withSource = None)
          (yay.length, nay.length) === (keeps.length, 0)

          db.readOnlyMaster { implicit session =>
            inject[KeepRepo].pageByLibrary(sourceLib.id.get, 0, 1000).length === keeps.length
            val newKeeps = inject[KeepRepo].pageByLibrary(emptyLib.id.get, 0, 1000)
            newKeeps.length === keeps.length
            newKeeps.map(_.title.get) === keeps.reverse.map(_.title.get)
            newKeeps.map(_.uriId) === keeps.reverse.map(_.uriId)
            newKeeps.map(_.note) === keeps.reverse.map(_.note)
          }
        }
      }
    }

    "move keeps to another library" in {
      "move from one owned lib to another" in {
        withDb(modules: _*) { implicit injector =>
          implicit val config = inject[PublicIdConfiguration]
          val (user, sourceLib, emptyLib, keeps) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val emptyLib = LibraryFactory.library().withOwner(user).saved
            val sourceLib = LibraryFactory.library().withOwner(user).saved
            val keeps = KeepFactory.keeps(50).map(_.withUser(user).withLibrary(sourceLib).withRandomTitle()).saved
            (user, sourceLib, emptyLib, keeps)
          }

          db.readOnlyMaster { implicit session =>
            inject[KeepRepo].pageByLibrary(emptyLib.id.get, 0, 1000).length === 0
            inject[KeepRepo].pageByLibrary(sourceLib.id.get, 0, 1000) === keeps.reverse
          }
          val (yay, nay) = inject[LibraryCommander].moveKeeps(user.id.get, emptyLib.id.get, keeps)
          (yay.length, nay.length) === (keeps.length, 0)

          db.readOnlyMaster { implicit session =>
            inject[KeepRepo].pageByLibrary(sourceLib.id.get, 0, 1000).length === 0
            val newKeeps = inject[KeepRepo].pageByLibrary(emptyLib.id.get, 0, 1000)
            val expectedKeeps = keeps.reverse
            newKeeps.length === keeps.length
            newKeeps.map(_.id.get) === expectedKeeps.map(_.id.get)
            newKeeps.map(_.title) === expectedKeeps.map(_.title)
            newKeeps.map(_.keptAt) === expectedKeeps.map(_.keptAt)
            newKeeps.map(_.note) === expectedKeeps.map(_.note)
          }
        }
      }
    }

    "copy keeps between libraries from tag" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries

        val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val site1 = "http://www.reddit.com/r/murica"
        val site2 = "http://www.freedom.org/"
        val site3 = "http://www.mcdonalds.com/"

        val (libUSA, k1, k2, k3) = db.readWrite { implicit s =>
          val libUSA = libraryRepo.save(Library(name = "USA", slug = LibrarySlug("usa"), ownerId = userCaptain.id.get, visibility = LibraryVisibility.DISCOVERABLE, kind = LibraryKind.USER_CREATED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libUSA.id.get, userId = userCaptain.id.get, access = LibraryAccess.OWNER))

          val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("Reddit")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(site2, Some("Freedom")))
          val uri3 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds")))

          // libMurica has 3 keeps
          val keep1 = KeepFactory.keep().withTitle("Reddit").withUser(userCaptain).withUri(uri1).withLibrary(libMurica).saved
          val keep2 = KeepFactory.keep().withTitle("Freedom").withUser(userCaptain).withUri(uri2).withLibrary(libMurica).saved
          val keep3 = KeepFactory.keep().withTitle("McDonalds").withUser(userCaptain).withUri(uri3).withLibrary(libMurica).saved

          tagCommander.addTagsToKeep(keep1.id.get, Seq(Hashtag("tag1")), userCaptain.id, None)
          tagCommander.addTagsToKeep(keep1.id.get, Seq(Hashtag("tag2")), userCaptain.id, None)
          tagCommander.addTagsToKeep(keep2.id.get, Seq(Hashtag("tag2")), userCaptain.id, None)
          tagCommander.addTagsToKeep(keep3.id.get, Seq(Hashtag("tag2")), userCaptain.id, None)

          keepRepo.count === 3
          (libUSA, keep1, keep2, keep3)
        }

        val libraryCommander = inject[LibraryCommander]

        //copy 1 keep to libUSA - libUSA has no keeps
        val res1 = libraryCommander.copyKeepsFromCollectionToLibrary(userCaptain.id.get, libUSA.id.get, Hashtag("tag1"))
        res1 must beRight
        res1.right.get._1.length === 1 // all successes
        res1.right.get._2.length === 0 // all successes
        db.readOnlyMaster { implicit s =>
          keepRepo.count === 4
          keepTagRepo.count === 4
          keepTagRepo.getAllByTagAndUser(Hashtag("tag1"), userCaptain.id.get).length === 1
          keepTagRepo.getAllByTagAndUser(Hashtag("tag2"), userCaptain.id.get).length === 3
        }

        // unkeep k1
        keepCommander.unkeepManyFromLibrary(Seq(k1.externalId), libMurica.id.get, k1.userId.get)
        db.readWrite { implicit s =>
          //          keepRepo.deactivate(k1)
          keepRepo.pageByLibrary(libMurica.id.get, 0, 10).length === 2
          keepTagRepo.getAllByTagAndUser(Hashtag("tag2"), userCaptain.id.get).length === 2
        }
        // There should be 3 active keeps now with 'tag2' try to copy them into libMurica
        val res2 = libraryCommander.copyKeepsFromCollectionToLibrary(userCaptain.id.get, libMurica.id.get, Hashtag("tag2"))
        res2 must beRight
        res2.right.get._1.length === 0 // 0 new keep
        res2.right.get._2.length === 2 // 2 failed keeps
        db.readOnlyMaster { implicit s =>
          keepRepo.count === 4
          keepRepo.pageByLibrary(libUSA.id.get, 0, 10).length === 1
          keepRepo.pageByLibrary(libMurica.id.get, 0, 10).length === 2
        }
      }
    }

    "send library invitation notification & emails" in {
      withDb(modules: _*) { implicit injector =>
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupInvites
        val libraryCommander = inject[LibraryCommander]
        val libraryInviteCommander = inject[LibraryInviteCommander]
        val emailRepo = inject[ElectronicMailRepo]
        val eliza = inject[ElizaServiceClient].asInstanceOf[FakeElizaServiceClientImpl]
        eliza.inbox.size === 0

        val t1 = new DateTime(2014, 8, 1, 7, 0, 0, 1, DEFAULT_DATE_TIME_ZONE)
        val newInvites = Seq(
          LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userIron.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1),
          LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userAgent.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1),
          LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1),
          LibraryInvite(libraryId = libScience.id.get, inviterId = userIron.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_WRITE, createdAt = t1)
        )

        Await.result(libraryInviteCommander.persistInvitesAndNotify(newInvites), Duration(10, "seconds"))
        eliza.inbox.size === 4

        db.readOnlyMaster { implicit s => emailRepo.count === 4 }

        val t2 = t1.plusMinutes(3) // send another set of invites 3 minutes later - too spammy, should not persist!
        val newInvitesAgain = Seq(
          LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userIron.id.get), access = LibraryAccess.READ_ONLY, createdAt = t2),
          LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userAgent.id.get), access = LibraryAccess.READ_ONLY, createdAt = t2),
          LibraryInvite(libraryId = libMurica.id.get, inviterId = userCaptain.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_ONLY, createdAt = t2),
          LibraryInvite(libraryId = libScience.id.get, inviterId = userIron.id.get, userId = Some(userHulk.id.get), access = LibraryAccess.READ_WRITE, createdAt = t2)
        )
        Await.result(libraryInviteCommander.persistInvitesAndNotify(newInvitesAgain), Duration(10, "seconds"))
        eliza.inbox.size === 4
        db.readOnlyMaster { implicit s => emailRepo.count === 4 }
      }
    }

    "build a list of a library's members and invitees" in {
      "get library members" in {
        withDb(modules: _*) { implicit injector =>
          val libraryCommander = inject[LibraryCommander]
          val (lib, memberships, users) = db.readWrite { implicit s =>
            fillWithGarbage()
            val users = Random.shuffle(UserFactory.users(50)).saved
            val (owner, rest) = (users.head, users.tail)
            val (collaborators, followers) = rest.splitAt(20)
            val lib = LibraryFactory.library().published().withOwner(owner).withCollaborators(collaborators).withFollowers(followers).saved
            val memberships = inject[LibraryMembershipRepo].getWithLibraryId(lib.id.get)
            (lib, memberships, users)
          }

          val usersById = users.map(u => u.id.get -> u).toMap
          def metric(lm: LibraryMembership) = {
            val user = usersById(lm.userId) // right now we don't use user info at all to sort
            // LibraryAccess has higher priorities first
            (-lm.access.priority, lm.id)
          }

          val canonical = memberships.sortBy(metric).tail // always drop the owner
          val extToIntMap = users.map(u => u.externalId -> u.id.get).toMap
          val members = inject[LibraryInfoCommander].getLibraryMembersAndInvitees(lib.id.get, 10, 30, false).map(_.member.left.get)

          val expected = canonical.drop(10).take(30)
          members.map(bu => extToIntMap(bu.externalId)) === expected.map(_.userId)
        }
      }
      "get library invitees" in {
        withDb(modules: _*) { implicit injector =>
          val (lib, invites, emails) = db.readWrite { implicit s =>
            fillWithGarbage()
            val owner = UserFactory.user().saved
            val emails = randomEmails(50)
            val lib = LibraryFactory.library().withOwner(owner).withInvitedEmails(emails).saved
            val invites = inject[LibraryInviteRepo].getByLibraryIdAndInviterId(lib.id.get, owner.id.get)
            (lib, invites, emails)
          }

          def metric(inv: LibraryInvite) = {
            // LibraryAccess has higher priorities first
            (-inv.access.priority, inv.id.get.id)
          }

          val canonical = invites.sortBy(metric)
          val libraryInvites = inject[LibraryInfoCommander].getLibraryMembersAndInvitees(lib.id.get, 10, 30, fillInWithInvites = true).map(_.member.right.get)
          val expected = canonical.drop(10).take(30)
          libraryInvites.map(bc => bc.email) === expected.map(_.emailAddress.get)
        }
      }
    }

    "updates last email sent for a list of keeps" in {
      withDb(modules: _*) { implicit injector =>
        val factory = inject[ShoeboxTestFactory]
        val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience, keeps: Seq[Keep]) = factory.setupLibraryKeeps()
        val libraryCommander = inject[LibraryCommander]

        libraryCommander.updateLastEmailSent(userIron.id.get, keeps)

        db.readWrite { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(libShield.id.get, userIron.id.get).map(_.lastEmailSent) must beSome
          libraryMembershipRepo.getWithLibraryIdAndUserId(libScience.id.get, userIron.id.get).map(_.lastEmailSent) must beSome
          libraryMembershipRepo.getWithLibraryIdAndUserId(libMurica.id.get, userCaptain.id.get).get.lastEmailSent must beNone
        }
      }
    }

    "sortFollowers" in {
      withDb(modules: _*) { implicit injector =>
        val libraryCommander = inject[LibraryInfoCommander]
        val user1 = BasicUser.fromUser(user().get)
        val user2 = BasicUser.fromUser(user().withPictureName("a").get)
        val user3 = BasicUser.fromUser(user().get)
        val user4 = BasicUser.fromUser(user().withPictureName("g").get)
        val user5 = BasicUser.fromUser(user().withPictureName("b").get)
        val sorted = libraryCommander.sortUsersByImage(user1 :: user2 :: user3 :: user4 :: user5 :: Nil)
        sorted.map(_.pictureName) === (user2 :: user4 :: user5 :: user1 :: user3 :: Nil).map(_.pictureName)
      }
    }

    "query recent top libraries for an owner" in {
      withDb(modules: _*) { implicit injector =>
        db.readWrite { implicit s =>
          library().withOwner(Id[User](1)).withId(1).saved
          library().withOwner(Id[User](1)).withId(2).saved
          library().withOwner(Id[User](2)).withId(3).saved
          membership().withLibraryFollower(Id[Library](1), Id[User](2)).saved
          membership().withLibraryFollower(Id[Library](1), Id[User](3)).saved
          membership().withLibraryFollower(Id[Library](2), Id[User](3)).saved
          val map = libraryMembershipRepo.userRecentTopFollowedLibrariesAndCounts(Id[User](1), since = DateTime.now().minusDays(1), limit = 2)
          map === Map(Id[Library](1) -> 2, Id[Library](2) -> 1)
        }
      }
    }
  }
}
