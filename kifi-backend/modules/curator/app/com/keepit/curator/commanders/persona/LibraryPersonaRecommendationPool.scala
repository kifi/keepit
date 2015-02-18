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

  import PersonaName2Id.name2Id

  private def toLibraryList(ids: Seq[Int]): Seq[Id[Library]] = ids.map { Id[Library](_) }

  def apply(userId: Id[User], libId: Id[Library]): LibraryRecommendation = {
    LibraryRecommendation(
      userId = userId,
      libraryId = libId,
      masterScore = fakeMasterScore,
      allScores = fakeLibraryScores
    )
  }

  val dev = Seq()
  val stu = Seq(47494, 50359, 26172, 47211, 41238, 48661, 51805, 45407, 27554, 27546, 26758)
  val techie = Seq(47284, 48709, 27570, 25000, 26464)
  val entre = Seq(27657, 27238, 24202, 28517, 25243, 25080, 25168, 25168, 46821, 27559)
  val art = Seq(24542, 49090, 28598, 30661, 41439, 48796, 50874, 50818, 27676, 24932, 45019, 26758)
  val foodie = Seq(49135, 26342, 46919, 47979, 50833, 31609, 28439, 25258, 45250, 46833, 26460, 47915)
  val science = Seq(25368, 44475, 51797, 31323, 26666, 50762, 28422, 30932, 26342)
  val fashion = Seq(27545, 51496, 49084)
  val health = Seq(47889, 47915, 47191, 49088, 41145, 30684, 28738, 31354, 44656, 25356)
  val investor = Seq(27569, 24203, 27559, 40373, 50862)
  val travel = Seq(30559, 48718, 47498, 46821, 27551, 26116, 50973, 51798, 27014, 32007, 46862, 49078, 50359, 49135)
  val gamer = Seq(50812, 47488, 42435, 47900, 42484, 44522, 42407)
  val parent = Seq(46862, 46860, 46861, 47438, 36680, 50551, 25345, 32127, 46824, 45472)
  val animal = Seq(49078, 49020, 27548, 49009, 28762, 24103, 43649, 28125)
  val thinker = Seq(25116, 28010, 42651, 28862, 27760, 50420, 31354, 49088)

  val recos: Map[Id[Persona], Seq[Id[Library]]] =
    Map(
      "dev" -> dev, "stu" -> stu, "techie" -> techie, "entre" -> entre, "art" -> art,
      "foodie" -> foodie, "science" -> science, "fashion" -> fashion, "health" -> health, "investor" -> investor,
      "travel" -> travel, "gamer" -> gamer, "animal" -> animal, "thinker" -> thinker
    ).map { case (a, b) => (name2Id(a), toLibraryList(b)) }
}
