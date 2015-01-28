package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.time.{ FakeClock, Clock }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import com.keepit.common.time._

class UserPersonaRepoTest extends Specification with ShoeboxTestInjector {
  "user persona repo" should {
    "work" in {
      withDb() { implicit injector =>
        val repo = inject[UserPersonaRepo]
        val clock = inject[Clock].asInstanceOf[FakeClock]
        val model = UserPersona(userId = Id[User](1), personaId = Id[Persona](1))
        val now = clock.now
        val editTime1 = now.plusHours(2)
        db.readWrite { implicit s =>
          repo.save(model)
          clock.push(editTime1)
          repo.save(model.copy(personaId = Id[Persona](2)))
        }

        db.readOnlyReplica { implicit s =>
          repo.getByUserAndPersona(Id[User](1), Id[Persona](1)).isDefined
          repo.getUserPersonaIds(Id[User](1)).sortBy(_.id).map { _.id }.toList === List(1, 2)
          val actives = repo.getUserActivePersonas(Id[User](1))
          (actives.personas zip actives.updatedAt).toMap.get(Id[Persona](2)).get === editTime1

        }

        val editTime2 = now.plusHours(4)

        db.readWrite { implicit s =>
          val model = repo.getByUserAndPersona(Id[User](1), Id[Persona](1)).get
          clock.push(editTime2)
          repo.save(model.copy(state = UserPersonaStates.INACTIVE))
          val actives = repo.getUserActivePersonas(Id[User](1))
          actives.personas.map { _.id } === List(2)
          actives.updatedAt.head === editTime1
        }
      }
    }
  }

}
