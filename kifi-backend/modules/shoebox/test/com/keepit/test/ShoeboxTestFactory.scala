package com.keepit.test

import com.google.inject.{ Injector, Singleton, Inject }
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.model._
import org.joda.time.DateTime
import com.keepit.model.UserFactoryHelper._

@Singleton
class ShoeboxTestFactory @Inject() (
    userConnRepo: UserConnectionRepo,
    userRepo: UserRepo,
    db: Database)(implicit injector: Injector) extends ShoeboxTestInjector {

  def createUsers()(implicit rw: RWSession) = {
    (
      UserFactory.user().withName("Aaron", "Paul").withUsername("test").saved,
      UserFactory.user().withName("Bryan", "Cranston").withUsername("test2").saved,
      UserFactory.user().withName("Anna", "Gunn").withUsername("test3").withEmailAddress("test@gmail.com").saved,
      UserFactory.user().withName("Dean", "Norris").withUsername("test4").saved
    )
  }

  def createUsersWithConnections()(implicit rw: RWSession): Seq[User] = {
    val (user1, user2, user3, user4) = createUsers()
    userConnRepo.save(UserConnection(user1 = user2.id.get, user2 = user3.id.get))
    userConnRepo.save(UserConnection(user1 = user2.id.get, user2 = user4.id.get))
    userConnRepo.save(UserConnection(user1 = user3.id.get, user2 = user4.id.get))
    userConnRepo.save(UserConnection(user1 = user1.id.get, user2 = user2.id.get))
    userConnRepo.save(UserConnection(user1 = user1.id.get, user2 = user3.id.get))

    Seq(user1, user2, user3, user4)
  }

  def setupUsers() = {
    val t1 = new DateTime(2014, 7, 4, 12, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val emailRepo = inject[UserEmailAddressRepo]
    val emailIron = EmailAddress("tony@stark.com")
    val emailCaptain = EmailAddress("steve.rogers@hotmail.com")
    val emailAgent = EmailAddress("samuelljackson@shield.com")
    val emailHulk = EmailAddress("incrediblehulk@gmail.com")

    val (userIron, userCaptain, userAgent, userHulk) = db.readWrite { implicit s =>
      val userIron = UserFactory.user().withName("Tony", "Stark").withUsername("ironman").withEmailAddress(emailIron).withCreatedAt(t1).saved
      val userCaptain = UserFactory.user().withName("Steve", "Rogers").withUsername("captainamerica").withEmailAddress(emailCaptain).withCreatedAt(t1).saved
      val userAgent = UserFactory.user().withName("Nick", "Fury").withUsername("agentfury").withEmailAddress(emailAgent).withCreatedAt(t1).saved
      val userHulk = UserFactory.user().withName("Bruce", "Banner").withUsername("incrediblehulk").withEmailAddress(emailHulk).withCreatedAt(t1).saved

      emailRepo.save(UserEmailAddress(userId = userIron.id.get, address = emailIron))
      emailRepo.save(UserEmailAddress(userId = userCaptain.id.get, address = emailCaptain))
      emailRepo.save(UserEmailAddress(userId = userAgent.id.get, address = emailAgent))
      emailRepo.save(UserEmailAddress(userId = userHulk.id.get, address = emailHulk))

      (userIron, userCaptain, userAgent, userHulk)
    }
    (userIron, userCaptain, userAgent, userHulk)
  }

  def setupLibraryKeeps() = {
    val uriRepo = inject[NormalizedURIRepo]
    val keepRepo = inject[KeepRepo]

    val (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience) = setupLibraries()

    val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val site1 = "http://www.reddit.com/r/murica"
    val site2 = "http://www.freedom.org/"
    val site3 = "http://www.mcdonalds.com/"

    val keeps: Seq[Keep] = db.readWrite { implicit s =>
      val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("Reddit")))
      val uri2 = uriRepo.save(NormalizedURI.withHash(site2, Some("Freedom")))
      val uri3 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds")))
      val uri4 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds1")))
      val uri5 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds2")))
      val uri6 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds3")).copy(restriction = Some(Restriction.ADULT)))
      val uri7 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds4")))
      val uri8 = uriRepo.save(NormalizedURI.withHash(site3, Some("McDonalds5")))

      val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
      val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
      val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))
      val url4 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri4.id.get))
      val url5 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri5.id.get))
      val url6 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri6.id.get))
      val url7 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri7.id.get))
      val url8 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri8.id.get))

      Seq(
        keepRepo.save(Keep(title = Some("Reddit"), userId = userCaptain.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(20), keptAt = t1.plusMinutes(20),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libShield.id.get), inDisjointLib = libShield.isDisjoint)),
        keepRepo.save(Keep(title = Some("Freedom"), userId = userCaptain.id.get, url = url2.url, urlId = url2.id.get,
          uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(100), keptAt = t1.plusMinutes(100),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libShield.id.get), inDisjointLib = libShield.isDisjoint)),
        keepRepo.save(Keep(title = Some("McDonalds"), userId = userIron.id.get, url = url3.url, urlId = url3.id.get,
          uriId = uri3.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(15), keptAt = t1.plusMinutes(15),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint)),
        keepRepo.save(Keep(title = Some("McDonalds1"), userId = userIron.id.get, url = url4.url, urlId = url4.id.get,
          uriId = uri4.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(25), keptAt = t1.plusMinutes(25),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint)),
        keepRepo.save(Keep(title = Some("McDonalds2"), userId = userIron.id.get, url = url5.url, urlId = url5.id.get,
          uriId = uri5.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(35), keptAt = t1.plusMinutes(35),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint)),
        keepRepo.save(Keep(title = Some("McDonalds3"), userId = userIron.id.get, url = url6.url, urlId = url6.id.get,
          uriId = uri6.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(45), keptAt = t1.plusMinutes(45),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint)),
        keepRepo.save(Keep(title = Some("McDonalds4"), userId = userIron.id.get, url = url7.url, urlId = url7.id.get,
          uriId = uri7.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(55), keptAt = t1.plusMinutes(55),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint)),
        keepRepo.save(Keep(title = Some("McDonalds5"), userId = userIron.id.get, url = url8.url, urlId = url8.id.get,
          uriId = uri8.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(65), keptAt = t1.plusMinutes(65),
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(libMurica.id.get), inDisjointLib = libMurica.isDisjoint)))
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience, keeps)
  }

  def setupLibraries() = {
    val libraryMembershipRepo = inject[LibraryMembershipRepo]
    val libraryRepo = inject[LibraryRepo]

    val (userIron, userCaptain, userAgent, userHulk) = setupUsers()
    val t1 = new DateTime(2014, 8, 1, 1, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val t2 = new DateTime(2014, 8, 1, 1, 0, 0, 1, DEFAULT_DATE_TIME_ZONE)
    val (libShield, libMurica, libScience) = db.readWrite { implicit s =>
      val libShield = libraryRepo.save(Library(name = "Avengers Missions", slug = LibrarySlug("avengers"),
        visibility = LibraryVisibility.SECRET, ownerId = userAgent.id.get, createdAt = t1, memberCount = 1))
      val libMurica = libraryRepo.save(Library(name = "MURICA", slug = LibrarySlug("murica"),
        visibility = LibraryVisibility.PUBLISHED, ownerId = userCaptain.id.get, createdAt = t1, memberCount = 1))
      val libScience = libraryRepo.save(Library(name = "Science & Stuff", slug = LibrarySlug("science"),
        visibility = LibraryVisibility.DISCOVERABLE, ownerId = userIron.id.get, createdAt = t1, memberCount = 1))

      libraryMembershipRepo.save(LibraryMembership(libraryId = libShield.id.get, userId = userAgent.id.get, access = LibraryAccess.READ_ONLY, createdAt = t2))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libShield.id.get, userId = userIron.id.get, access = LibraryAccess.OWNER, createdAt = t2))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libMurica.id.get, userId = userCaptain.id.get, access = LibraryAccess.READ_ONLY, createdAt = t2))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libScience.id.get, userId = userIron.id.get, access = LibraryAccess.READ_ONLY, createdAt = t2))
      libraryMembershipRepo.save(LibraryMembership(libraryId = libMurica.id.get, userId = userAgent.id.get, access = LibraryAccess.READ_ONLY, createdAt = t2))
      (libShield, libMurica, libScience)
    }
    (userIron, userCaptain, userAgent, userHulk, libShield, libMurica, libScience)
  }
}
