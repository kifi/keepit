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
  val specialBoostSet = Set.empty[Id[NormalizedURI]]
  val specialBoostScore = 0f

  def generateRecoItemFromId(userId: Id[User], recoId: Id[NormalizedURI], applyBoost: Boolean): UriRecommendation = {
    val perturb = perturbation(applyBoost)
    FixedSetURIReco(userId, recoId, perturb)
  }
}

object FixedSetURIReco {
  val fakeMasterScore: Float = 18f // empirically the upper bound. becomes invalid if scoring method changes
  private def fakeUriScores: UriScores = UriScores(socialScore = 1f, popularityScore = 1f, overallInterestScore = 1f, recentInterestScore = 1f, recencyScore = 1f, priorScore = 1f, rekeepScore = 1f, discoveryScore = 1f, curationScore = None, multiplier = None, libraryInducedScore = None)
  import PersonaName2Id.name2Id

  private def toURIList(ids: Seq[Int]): Seq[Id[NormalizedURI]] = ids.map { Id[NormalizedURI](_) }

  def apply(userId: Id[User], recoId: Id[NormalizedURI], perturbation: Float): UriRecommendation = {
    UriRecommendation(
      uriId = recoId,
      userId = userId,
      masterScore = fakeMasterScore + perturbation,
      allScores = fakeUriScores,
      attribution = SeedAttribution.EMPTY,
      topic1 = None,
      topic2 = None)
  }

  val dev = Seq(3598759, 4126244, 245922, 182671, 219058, 261767, 318768, 338680, 817582, 949853, 1258030, 1341990, 1357499, 1431193, 2146571, 2804463, 3480155, 3548350, 3548381, 3778192, 3781948)
  val stu = Seq(4082885, 4269550, 4197996, 4275748, 4244239, 3739014, 2207397, 3740249, 3739922, 3739242, 2150346, 3304531, 4387262, 2257299, 4417323, 4421232, 1992480, 4417341, 4422162)
  val techie = Seq(4271089, 4270635, 941589, 2678889, 3064362, 2959970, 2963128, 3808600, 1789933, 4046713, 4420717, 1442088, 4073631, 1627394, 4221645, 3825247, 2897101, 3051429, 3052109, 2757870)
  val entre = Seq(1130168, 3822739, 4202423, 3165175, 1431596, 1968521, 2876852, 2055027, 1968517, 4076277, 4241599, 1108681, 1968522, 4102803, 3889507, 3784915, 3515752, 2959918, 3143556, 3143359, 665227, 3132970, 3031172)
  val art = Seq(1293966, 1837187, 4031821, 1317203, 3739130, 1881758, 3420396, 1943830, 4420823, 4385804, 4300390, 3528385, 3219868, 4270626, 4269465, 4120280, 4120271, 926969, 4120268, 4120262)
  val foodie = Seq(4120379, 4044995, 4197877, 4197899, 4275938, 4270875, 4276233, 3738938, 4121063, 4123678, 3603631, 1980182, 1058475, 3605496, 4422088, 4422070, 4422049, 4422046, 4422036)
  val science = Seq(1133375, 4169689, 4275749, 4197848, 4197856, 4275780, 4275778, 4275679, 3470461, 1036478, 2834631, 4269589, 4270602, 3143243, 3740531, 1149478, 2803234, 3193800, 3410342, 3088515, 2608637, 4420787, 4420777, 4387262)
  val fashion = Seq(2961453, 2959027, 3054624, 4197999, 4197921, 4275808, 3738987, 4415400, 3739171, 3854843, 3738997, 1938621, 3739012, 4410650, 4415421, 4415270, 3054749, 4415041, 4422084)
  val health = Seq(3556508, 3554566, 3515778, 3470132, 2244187, 4080549, 4269814, 4407072, 1067020, 864518, 4053868, 4222009, 2072421, 2227180, 4228221, 1778717, 2876135, 4233240, 1341006, 2891219, 2877142, 4422063)
  val investor = Seq(3461742, 2959015, 4198017, 4198028, 2896932, 4235828, 1464147, 3940501, 1371687, 3713352, 3457994, 2038350, 2960589, 2960991, 2958928, 4648511, 4417869, 4402225, 4116872)
  val travel = Seq(2825215, 4102772, 4119986, 3740204, 4261650, 4201963, 3738938, 3741086, 4123616, 1885381, 2824614, 2290218, 2305648, 1148972, 2285566, 4398175, 4422134, 4422128, 4422123, 4422119, 4422145)
  val gamer = Seq(4210195, 4261443, 1846078, 4276165, 3607580, 4213139, 4377442, 1646075, 3072725, 4306892, 4153906, 3852427, 4409106, 3534064, 2207950)
  val parent = Seq(3945722, 3920131, 2207950, 4169117, 4209465, 4250849, 4053821, 4027256, 4377894, 1455292, 3193780, 1805455, 4053821, 4419919, 3292935, 4419186, 3036922, 1372264, 1466941, 2879674, 1967883, 4120097, 4120083, 4420817)
  val animal = Seq(4271089, 4270840, 3924023, 4269499, 3648562, 3740128, 3739991, 2000992, 3578798, 4420768, 4420760, 4397923, 4420752, 4420747, 4420727, 4420719, 2230698, 4420716, 4420713, 4420697, 4420689, 4420686, 1611657)
  val thinker = Seq(3159092, 4198326, 3821349, 2514985, 2775205, 3475180, 4270602, 4296328, 4387262, 3823137, 3202151, 4395640, 4345436, 2825246, 3473880, 3822418, 1042778, 3494118, 864281, 1152175, 2229366, 2879042)

  val recos: Map[Id[Persona], Seq[Id[NormalizedURI]]] =
    Map(
      "dev" -> dev, "stu" -> stu, "techie" -> techie, "entre" -> entre, "art" -> art,
      "foodie" -> foodie, "science" -> science, "fashion" -> fashion, "health" -> health, "investor" -> investor,
      "travel" -> travel, "gamer" -> gamer, "parent" -> parent, "animal" -> animal, "thinker" -> thinker
    ).map { case (a, b) => (name2Id(a), toURIList(b)) }

}
