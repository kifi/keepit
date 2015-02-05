package com.keepit.curator.commanders.persona

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.Id
import com.keepit.curator.model.{ LibraryScores, LibraryRecommendation }
import com.keepit.model.{ User, Library, Persona }

@ImplementedBy(classOf[LibraryFixedSetPersonaRecoPool])
trait LibraryPersonaRecommendationPool extends PersonaRecommendationPool[LibraryRecommendation]

@Singleton
class LibraryFixedSetPersonaRecoPool @Inject() () extends FixedSetPersonaRecoPool[Library, LibraryRecommendation] with LibraryPersonaRecommendationPool {
  val fixedRecos = FixedSetLibraryReco.recos

  def generateRecoItemFromId(userId: Id[User], recoId: Id[Library]): LibraryRecommendation = {
    FixedSetLibraryReco(userId, recoId)
  }
}

object FixedSetLibraryReco {

  private def fakeMasterScore = 100f
  private def fakeLibraryScores: LibraryScores = LibraryScores(socialScore = 1f, interestScore = 1f, recencyScore = 1f, popularityScore = 1f, sizeScore = 1f, contentScore = None)

  def apply(userId: Id[User], libId: Id[Library]): LibraryRecommendation = {
    LibraryRecommendation(
      userId = userId,
      libraryId = libId,
      masterScore = fakeMasterScore,
      allScores = fakeLibraryScores
    )
  }

  val recos = Map.empty[Id[Persona], Seq[Id[Library]]]
}
