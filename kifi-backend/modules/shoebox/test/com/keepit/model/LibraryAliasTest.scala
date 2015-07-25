package com.keepit.model

import org.specs2.mutable.Specification
import com.keepit.test.ShoeboxTestInjector
import com.keepit.common.db.Id

class LibraryAliasTest extends Specification with ShoeboxTestInjector {

  val léo = LibrarySpace.UserSpace(Id[User](134))
  val kifi = LibrarySpace.OrganizationSpace(Id[Organization](1))

  val theBest = LibrarySlug(LibrarySlug.generateFromName("the best library in the world"))
  val theWorst = LibrarySlug(LibrarySlug.generateFromName("the worst library ever"))

  val theBestLibraryId = Id[Library](1)
  val theBetterBestLibraryId = Id[Library](2)
  val theNotSoGreatBestLibraryId = Id[Library](3)

  "libraryAliasRepo" should {

    "alias an owner id and a library slug to a single library" in {
      withDb() { implicit injector =>

        // first user

        val theBestFromLéoAlias = db.readWrite { implicit session => libraryAliasRepo.alias(léo, theBest, theBestLibraryId) }
        theBestFromLéoAlias.space === léo
        theBestFromLéoAlias.slug === theBest
        theBestFromLéoAlias.libraryId === theBestLibraryId
        theBestFromLéoAlias.state === LibraryAliasStates.ACTIVE

        val betterBestFromLéoAlias = db.readWrite { implicit session =>
          libraryAliasRepo.alias(léo, theBest, theBestLibraryId) === theBestFromLéoAlias
          libraryAliasRepo.alias(léo, theBest, theBetterBestLibraryId)
        }

        betterBestFromLéoAlias.id.get === theBestFromLéoAlias.id.get
        betterBestFromLéoAlias.libraryId === theBetterBestLibraryId

        // second user

        val theBestFromKifiAlias = db.readWrite { implicit session =>
          libraryAliasRepo.alias(kifi, theBest, theNotSoGreatBestLibraryId)
        }

        theBestFromKifiAlias.id.get !== theBestFromLéoAlias.id.get
        theBestFromKifiAlias.space === kifi
        theBestFromKifiAlias.slug === theBest
        theBestFromKifiAlias.libraryId === theNotSoGreatBestLibraryId
        theBestFromKifiAlias.state === LibraryAliasStates.ACTIVE

        val theWorstFromKifiAlias = db.readWrite { implicit session =>
          libraryAliasRepo.alias(kifi, theWorst, theNotSoGreatBestLibraryId)
        }

        theWorstFromKifiAlias.id.get !== theBestFromKifiAlias.id.get
        theWorstFromKifiAlias.space === kifi
        theWorstFromKifiAlias.slug === theWorst
        theWorstFromKifiAlias.libraryId === theNotSoGreatBestLibraryId
        theWorstFromKifiAlias.state === LibraryAliasStates.ACTIVE
      }
    }

    "get active aliases by owner id and library slug" in {
      withDb() { implicit injector =>
        val theBestFromLéoAlias = db.readWrite { implicit session => libraryAliasRepo.alias(léo, theBest, theBestLibraryId) }
        db.readOnlyMaster { implicit session =>
          libraryAliasRepo.getBySpaceAndSlug(léo, theBest) === Some(theBestFromLéoAlias)
        }

        db.readWrite { implicit session => libraryAliasRepo.save(theBestFromLéoAlias.copy(state = LibraryAliasStates.INACTIVE)) }
        db.readOnlyMaster { implicit session =>
          libraryAliasRepo.getBySpaceAndSlug(léo, theBest) === None
        }
      }
    }
  }
}

