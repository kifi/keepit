package com.keepit.model

import com.google.inject.Injector
import com.keepit.common.time._
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.model.UserFactoryHelper._

class LibraryInviteTest extends Specification with ShoeboxTestInjector {

  def setup()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 7, 4, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

    db.readWrite { implicit s =>
      val user1 = UserFactory.user().withCreatedAt(t1).withName("Aaron", "H").withUsername("test").saved
      val user2 = UserFactory.user().withCreatedAt(t1.plusHours(2)).withName("Jackie", "Chan").withUsername("test2").saved
      val library1 = libraryRepo.save(Library(name = "Lib1", ownerId = user1.id.get, createdAt = t1.plusMinutes(2),
        visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("A"), memberCount = 1))
      val library2 = libraryRepo.save(Library(name = "Lib2", ownerId = user2.id.get, createdAt = t1.plusMinutes(5),
        visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("B"), memberCount = 1))
      val inv1 = libraryInviteRepo.save(LibraryInvite(libraryId = library1.id.get, inviterId = user1.id.get, userId = Some(user2.id.get),
        access = LibraryAccess.READ_WRITE, createdAt = t1.plusHours(1)))
      val inv2 = libraryInviteRepo.save(LibraryInvite(libraryId = library2.id.get, inviterId = user2.id.get, userId = Some(user1.id.get),
        access = LibraryAccess.READ_ONLY, createdAt = t1.plusHours(2)))
      (library1, library2, user1, user2, inv1, inv2)
    }
  }

  "LibraryInviteRepo" should {
    "basically work" in { // test read/write/save
      withDb() { implicit injector =>
        val (lib1, lib2, user1, user2, inv1, inv2) = setup()
        val all = db.readOnlyMaster(implicit session => libraryInviteRepo.all)
        all.map(_.inviterId) === Seq(user1.id.get, user2.id.get)
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
          libraryInviteRepo.save(LibraryInvite(libraryId = lib1.id.get, inviterId = user1.id.get, userId = Some(user2.id.get),
            access = LibraryAccess.READ_WRITE, createdAt = t1.plusHours(1)))
        }
        db.readWrite { implicit s =>
          libraryInviteRepo.count === 2
        }
      }
    }

  }
}
