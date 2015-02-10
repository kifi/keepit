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
  import PersonaName2Id.name2Id

  private def toURIList(ids: Seq[Int]): Seq[Id[NormalizedURI]] = ids.map { Id[NormalizedURI](_) }

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

  val dev = Seq()
  val stu = Seq(4082885)
  val techie = Seq()
  val entre = List(1130168, 4202342, 2825215)
  val art = Seq(1293966, 1837187, 4031821)
  val foodie = Seq(3556508, 4120379, 4044995, 4197877, 4197899)
  val science = Seq(3740204, 3470132, 4197899, 1133375, 4169689, 4197848, 4197856)
  val fashion = Seq(2961453, 2959027, 3054624, 4197999, 4197921)
  val health = Seq(3556508, 3554566, 3515778, 3470132)
  val investor = Seq(3461742, 2959015, 4198017, 4198028)
  val travel = Seq(4197999, 2825215, 4102772, 4119986, 3740204)
  val gamer = Seq()
  val parent = Seq(3945722, 3920131, 2207950, 4169117, 3461742)
  val animal = Seq()
  val thinker = Seq(3159092, 4198326)

  val recos: Map[Id[Persona], Seq[Id[NormalizedURI]]] =
    Map(
      "dev" -> dev, "stu" -> stu, "techie" -> techie, "entre" -> entre, "art" -> art,
      "foodie" -> foodie, "science" -> science, "fashion" -> fashion, "health" -> health, "investor" -> investor,
      "travel" -> travel, "gamer" -> gamer, "animal" -> animal, "thinker" -> thinker
    ).map { case (a, b) => (name2Id(a), toURIList(b)) }

}
