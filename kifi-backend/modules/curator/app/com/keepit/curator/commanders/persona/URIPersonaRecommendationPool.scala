package com.keepit.curator.commanders.persona

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.db.Id
import com.keepit.curator.model.{ SeedAttribution, UriScores, UriRecommendation }
import com.keepit.model.{ User, NormalizedURI, Persona }

@ImplementedBy(classOf[URIFixedSetPersonaRecoPool])
trait URIPersonaRecommendationPool extends PersonaRecommendationPool[UriRecommendation]

@Singleton
class URIFixedSetPersonaRecoPool @Inject() () extends FixedSetPersonaRecoPool[NormalizedURI, UriRecommendation] with URIPersonaRecommendationPool {
  val fixedRecos = FixedSetURIReco.recos

  def generateRecoItemFromId(userId: Id[User], recoId: Id[NormalizedURI]): UriRecommendation = {
    FixedSetURIReco(userId, recoId)
  }
}

object FixedSetURIReco {
  private def fakeMasterScore: Float = 100f
  private def fakeUriScores: UriScores = UriScores(socialScore = 1f, popularityScore = 1f, overallInterestScore = 1f, recentInterestScore = 1f, recencyScore = 1f, priorScore = 1f, rekeepScore = 1f, discoveryScore = 1f, curationScore = None, multiplier = None, libraryInducedScore = None)

  def apply(userId: Id[User], recoId: Id[NormalizedURI]): UriRecommendation = {
    UriRecommendation(
      uriId = recoId,
      userId = userId,
      masterScore = fakeMasterScore,
      allScores = fakeUriScores,
      attribution = SeedAttribution.EMPTY,
      topic1 = None,
      topic2 = None)
  }

  val recos = Map.empty[Id[Persona], Seq[Id[NormalizedURI]]]
}
