package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.time._
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification

class LibraryInviteTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val u1 = new User(firstName = "Aaron", lastName = "H", createdAt = t1)
    val u2 = new User(firstName = "Jackie", lastName = "Chan", createdAt = t1.plusHours(2))

    db.readWrite { implicit s =>
      val user1 = userRepo.save(u1)
      val user2 = userRepo.save(u2)
      val library1 = libraryRepo.save(Library(name = "Lib1", ownerId = user1.id.get, createdAt = t1.plusMinutes(2),
        visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("A"), memberCount = 1))
      val library2 = libraryRepo.save(Library(name = "Lib2", ownerId = user2.id.get, createdAt = t1.plusMinutes(5),
        visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("B"), memberCount = 1))
      val inv1 = libraryInviteRepo.save(LibraryInvite(libraryId = library1.id.get, ownerId = user1.id.get, userId = Some(user2.id.get),
        access = LibraryAccess.READ_WRITE, createdAt = t1.plusHours(1)))
      val inv2 = libraryInviteRepo.save(LibraryInvite(libraryId = library2.id.get, ownerId = user2.id.get, userId = Some(user1.id.get),
        access = LibraryAccess.READ_ONLY, createdAt = t1.plusHours(2)))
      (library1, library2, user1, user2, inv1, inv2)
    }
  }

  "LibraryInviteRepo" should {
    "basically work" in { // test read/write/save
      withDb() { implicit injector =>
        val (lib1, lib2, user1, user2, inv1, inv2) = setup()
        val all = db.readOnlyMaster(implicit session => libraryInviteRepo.all)
        all.map(_.ownerId) === Seq(user1.id.get, user2.id.get)
        all.map(_.userId.get) === Seq(user2.id.get, user1.id.get)
      }
    }

    "invalidate cache when delete" in {
      withDb() { implicit injector =>
        val (lib1, lib2, user1, user2, inv1, inv2) = setup()
        db.readWrite { implicit s =>
          libraryInviteRepo.count === 2
          val libInv = libraryInviteRepo.get(inv1.id.get)
          libraryInviteRepo.delete(libInv)
        }
        db.readWrite { implicit s =>
          libraryInviteRepo.all.size === 1
          libraryInviteRepo.count === 1
        }
        db.readWrite { implicit s =>
          val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
          libraryInviteRepo.save(LibraryInvite(libraryId = lib1.id.get, ownerId = user1.id.get, userId = Some(user2.id.get),
            access = LibraryAccess.READ_WRITE, createdAt = t1.plusHours(1)))
        }
        db.readWrite { implicit s =>
          libraryInviteRepo.count === 2
        }
      }
    }

  }
}
