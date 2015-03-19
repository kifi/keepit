package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.time._
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification

class LibraryMembershipTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

    db.readWrite { implicit s =>
      val user1 = userRepo.save(User(firstName = "Aaron", lastName = "H", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
      val user2 = userRepo.save(User(firstName = "Jackie", lastName = "Chan", createdAt = t1.plusHours(2), username = Username("test2"), normalizedUsername = "test2"))
      val library1 = libraryRepo.save(Library(name = "Lib1", ownerId = user1.id.get, createdAt = t1.plusMinutes(2), lastKept = Some(t1.plusMinutes(2)),
        visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("A"), memberCount = 1))
      val library2 = libraryRepo.save(Library(name = "Lib2", ownerId = user2.id.get, createdAt = t1.plusMinutes(5), lastKept = Some(t1.plusMinutes(2)),
        visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("B"), memberCount = 1))
      val lm1 = libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user1.id.get,
        access = LibraryAccess.OWNER, createdAt = t1.plusHours(1)))
      val lm2 = libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user2.id.get,
        access = LibraryAccess.READ_ONLY, createdAt = t1.plusHours(2)))
      val lm3 = libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = user2.id.get,
        access = LibraryAccess.OWNER, createdAt = t1.plusHours(3)))
      val lm4 = libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = user1.id.get,
        access = LibraryAccess.OWNER, createdAt = t1.plusHours(4)))
      (library1, library2, user1, user2, lm1, lm2, lm3, lm4, t1)
    }
  }

  "LibraryMembershipRepo" should {
    "basically work" in { // test read/write/save
      withDb() { implicit injector =>
        val (library1, library2, user1, user2, lm1, lm2, lm3, lm4, t1) = setup()
        val all = db.readOnlyMaster(implicit session => libraryMembershipRepo.all)
        all.map(_.access) === Seq(LibraryAccess.OWNER, LibraryAccess.READ_ONLY, LibraryAccess.OWNER, LibraryAccess.OWNER)
        db.readOnlyMaster { implicit session =>
          libraryMembershipRepo.countWithUserIdAndAccess(user1.id.get, LibraryAccess.OWNER) === 2
          libraryMembershipRepo.countWithUserIdAndAccess(user1.id.get, LibraryAccess.READ_ONLY) === 0
          libraryMembershipRepo.countWithUserIdAndAccess(user2.id.get, LibraryAccess.OWNER) === 1
          libraryMembershipRepo.countWithUserIdAndAccess(user2.id.get, LibraryAccess.READ_ONLY) === 1
        }
      }
    }

    "getLatestUpdatedLibraryUserFollow" in {
      withDb() { implicit injector =>
        val (library1, library2, user1, user2, lm1, lm2, lm3, lm4, t1) = setup()
        db.readOnlyMaster { implicit session =>
          libraryMembershipRepo.getLatestUpdatedLibraryUserFollow(user1.id.get) === None
          libraryMembershipRepo.getLatestUpdatedLibraryUserFollow(user2.id.get).get === library1
        }
      }
    }

    "invalidate cache when delete" in {
      withDb() { implicit injector =>
        val (lib1, lib2, user1, user2, lm1, lm2, lm3, lm4, t1) = setup()
        db.readWrite { implicit s =>
          libraryMembershipRepo.count === 4
          val libMem = libraryMembershipRepo.get(lm1.id.get)
          libraryMembershipRepo.delete(libMem)
        }
        db.readWrite { implicit s =>
          libraryMembershipRepo.all.size === 3
          libraryMembershipRepo.count === 3
        }
        db.readWrite { implicit s =>
          val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get,
            access = LibraryAccess.READ_WRITE, createdAt = t1.plusHours(1)))
        }
        db.readWrite { implicit s =>
          libraryMembershipRepo.count === 4
        }
      }
    }

  }
}
