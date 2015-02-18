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
  val stu = Seq(4082885, 4269550, 4197996, 4275748, 4244239, 3739014, 2207397, 3740249, 3739922, 1779222, 3739242, 2150346, 3304531)
  val techie = Seq(4271089, 4269555, 4270635, 941589)
  val entre = List(1130168, 4202342, 3822739, 4202423, 3165175)
  val art = Seq(1293966, 1837187, 4031821, 1317203)
  val foodie = Seq(4120379, 4044995, 4197877, 4197899, 4275938, 4270875, 4276233, 3738938)
  val science = Seq(1133375, 4169689, 4275749, 4197848, 4197856, 4275780, 4275778, 4275679, 3470461, 1036478, 2834631, 4269589, 4270602, 3143243, 3740531)
  val fashion = Seq(2961453, 2959027, 3054624, 4197999, 4197921, 4275808, 4266699, 3738987)
  val health = Seq(3556508, 3554566, 3515778, 3470132, 2244187, 4080549, 4269814)
  val investor = Seq(3461742, 2959015, 4198017, 4198028, 2896932, 4235828)
  val travel = Seq(2825215, 4102772, 4119986, 3740204, 4261650, 4201963, 3738938, 3741086)
  val gamer = Seq(4210195, 4261443, 1846078, 4276165)
  val parent = Seq(3945722, 3920131, 2207950, 4169117, 4209465, 4250849)
  val animal = Seq(4271089, 4270840, 3924023, 4269499, 3648562, 3740128, 3739991)
  val thinker = Seq(3159092, 4198326, 3821349, 2514985, 2775205, 3475180, 4270602)

  val recos: Map[Id[Persona], Seq[Id[NormalizedURI]]] =
    Map(
      "dev" -> dev, "stu" -> stu, "techie" -> techie, "entre" -> entre, "art" -> art,
      "foodie" -> foodie, "science" -> science, "fashion" -> fashion, "health" -> health, "investor" -> investor,
      "travel" -> travel, "gamer" -> gamer, "animal" -> animal, "thinker" -> thinker
    ).map { case (a, b) => (name2Id(a), toURIList(b)) }

}
