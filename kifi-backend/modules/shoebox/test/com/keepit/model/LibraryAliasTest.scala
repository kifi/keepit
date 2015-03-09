package com.keepit.model

import org.specs2.mutable.Specification
import com.keepit.test.ShoeboxTestInjector
import com.keepit.common.db.Id

class LibraryAliasTest extends Specification with ShoeboxTestInjector {

  val firstUser = Id[User](134)
  val secondUser = Id[User](-134)

  val theBest = LibrarySlug(LibrarySlug.generateFromName("the best library in the world"))
  val theWorst = LibrarySlug(LibrarySlug.generateFromName("the worst library ever"))

  val theBestLibraryId = Id[Library](1)
  val theBetterBestLibraryId = Id[Library](2)
  val theNotSoGreatBestLibraryId = Id[Library](3)

  "libraryAliasRepo" should {

    "alias an owner id and a library slug to a single library" in {
      withDb() { implicit injector =>

        // first user

        val theBestFromFirstUserAlias = db.readWrite { implicit session => libraryAliasRepo.alias(firstUser, theBest, theBestLibraryId) }
        theBestFromFirstUserAlias.ownerId === firstUser
        theBestFromFirstUserAlias.slug === theBest
        theBestFromFirstUserAlias.libraryId == theBestLibraryId
        theBestFromFirstUserAlias.state === LibraryAliasStates.ACTIVE

        val betterBestFromFirstUserAlias = db.readWrite { implicit session =>
          libraryAliasRepo.alias(firstUser, theBest, theBestLibraryId) === theBestFromFirstUserAlias
          libraryAliasRepo.alias(firstUser, theBest, theBetterBestLibraryId)
        }

        betterBestFromFirstUserAlias.id.get === theBestFromFirstUserAlias.id.get
        betterBestFromFirstUserAlias.libraryId === theBetterBestLibraryId

        // second user

        val theBestFromSecondUserAlias = db.readWrite { implicit session =>
          libraryAliasRepo.alias(secondUser, theBest, theNotSoGreatBestLibraryId)
        }

        theBestFromSecondUserAlias.id.get !== theBestFromFirstUserAlias.id.get
        theBestFromSecondUserAlias.ownerId === secondUser
        theBestFromSecondUserAlias.slug === theBest
        theBestFromSecondUserAlias.libraryId == theNotSoGreatBestLibraryId
        theBestFromSecondUserAlias.state === LibraryAliasStates.ACTIVE

        val theWorstFromSecondUserAlias = db.readWrite { implicit session =>
          libraryAliasRepo.alias(secondUser, theWorst, theNotSoGreatBestLibraryId)
        }

        theWorstFromSecondUserAlias.id.get !== theBestFromSecondUserAlias.id.get
        theWorstFromSecondUserAlias.ownerId === secondUser
        theWorstFromSecondUserAlias.slug === theWorst
        theWorstFromSecondUserAlias.libraryId == theNotSoGreatBestLibraryId
        theWorstFromSecondUserAlias.state === LibraryAliasStates.ACTIVE
      }
    }

    "get active aliases by owner id and library slug" in {
      withDb() { implicit injector =>
        val theBestFromFirstUserAlias = db.readWrite { implicit session => libraryAliasRepo.alias(firstUser, theBest, theBestLibraryId) }
        db.readOnlyMaster { implicit session =>
          libraryAliasRepo.getByOwnerIdAndSlug(firstUser, theBest) === Some(theBestFromFirstUserAlias)
        }

        db.readWrite { implicit session => libraryAliasRepo.save(theBestFromFirstUserAlias.copy(state = LibraryAliasStates.INACTIVE)) }
        db.readOnlyMaster { implicit session =>
          libraryAliasRepo.getByOwnerIdAndSlug(firstUser, theBest) === None
        }
      }
    }
  }
}

