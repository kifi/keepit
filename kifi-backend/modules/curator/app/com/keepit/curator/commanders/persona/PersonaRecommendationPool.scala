package com.keepit.curator.commanders.persona

import com.keepit.common.db.Id
import com.keepit.model.{ User, Persona }

trait PersonaRecommendationPool[R] {
  def getUserRecosByPersona(userId: Id[User], pid: Id[Persona]): Seq[R]
}

// T: URI or Library. R: reco type
trait FixedSetPersonaRecoPool[T, R] extends PersonaRecommendationPool[R] {

  protected val fixedRecos: Map[Id[Persona], Seq[Id[T]]]
  protected val specialBoostSet: Set[Id[T]]
  protected val specialBoostScore: Float

  def perturbation(hasSpecialBoost: Boolean): Float = {
    if (hasSpecialBoost) specialBoostScore + util.Random.nextFloat() else util.Random.nextFloat()
  }

  def getRecoIdsByPersona(pid: Id[Persona]): Seq[Id[T]] = fixedRecos.getOrElse(pid, Seq())

  def generateRecoItemFromId(userId: Id[User], recoId: Id[T], applyBoost: Boolean): R

  override def getUserRecosByPersona(userId: Id[User], pid: Id[Persona]): Seq[R] = {
    getRecoIdsByPersona(pid).map { recoId =>
      val boost = specialBoostSet.contains(recoId)
      generateRecoItemFromId(userId, recoId, boost)
    }
  }
}

private[persona] object PersonaName2Id {
  private implicit def toPersonaId(x: Int): Id[Persona] = Id[Persona](x)

  val name2Id: Map[String, Id[Persona]] = Map("dev" -> 1, "stu" -> 2, "techie" -> 3, "entre" -> 4, "art" -> 5,
    "foodie" -> 6, "science" -> 7, "fashion" -> 8, "health" -> 9, "investor" -> 10,
    "travel" -> 11, "gamer" -> 12, "parent" -> 13, "animal" -> 14, "thinker" -> 15)
}
