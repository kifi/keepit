package com.keepit.curator.commanders.persona

import com.keepit.common.db.Id
import com.keepit.model.{ User, Persona }

trait PersonaRecommendationPool[R] {
  def getUserRecosByPersona(userId: Id[User], pid: Id[Persona]): Seq[R]
}

// T: URI or Library. R: reco type
trait FixedSetPersonaRecoPool[T, R] extends PersonaRecommendationPool[R] {

  protected val fixedRecos: Map[Id[Persona], Seq[Id[T]]]

  def getRecoIdsByPersona(pid: Id[Persona]): Seq[Id[T]] = fixedRecos.getOrElse(pid, Seq())

  def generateRecoItemFromId(userId: Id[User], recoId: Id[T]): R

  override def getUserRecosByPersona(userId: Id[User], pid: Id[Persona]): Seq[R] = {
    getRecoIdsByPersona(pid).map { recoId => generateRecoItemFromId(userId, recoId) }
  }
}
