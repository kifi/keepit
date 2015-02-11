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
  val stu = Seq(47494, 50359, 26172, 47211)
  val techie = Seq(47284)
  val entre = Seq(46821, 27657, 27238)
  val art = Seq(24542, 49090, 28598, 30661)
  val foodie = Seq(47915, 49135, 26342, 46919)
  val science = Seq(26342, 25368)
  val fashion = Seq(49084, 27545)
  val health = Seq(47438, 47889, 47915, 49088)
  val investor = Seq(24203, 27569)
  val travel = Seq(46862, 49078, 48718, 47498, 46821, 27551, 26116, 50359, 49135)
  val gamer = Seq()
  val parent = Seq(46862, 46860, 46861, 47438, 40380, 36680)
  val animal = Seq(27548, 49078, 49020, 49009)
  val thinker = Seq(47191, 49088, 25116, 28010)

  val recos: Map[Id[Persona], Seq[Id[Library]]] =
    Map(
      "dev" -> dev, "stu" -> stu, "techie" -> techie, "entre" -> entre, "art" -> art,
      "foodie" -> foodie, "science" -> science, "fashion" -> fashion, "health" -> health, "investor" -> investor,
      "travel" -> travel, "gamer" -> gamer, "animal" -> animal, "thinker" -> thinker
    ).map { case (a, b) => (name2Id(a), toLibraryList(b)) }
}
