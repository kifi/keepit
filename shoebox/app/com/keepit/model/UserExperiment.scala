package com.keepit.model

import com.google.inject.{Inject, ImplementedBy, Singleton}
import com.keepit.common.cache.{FortyTwoCache, FortyTwoCachePlugin, Key}
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scala.concurrent.duration._

case class UserExperiment (
  id: Option[Id[UserExperiment]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  experimentType: State[ExperimentType],
  state: State[UserExperiment] = UserExperimentStates.ACTIVE
) extends Model[UserExperiment] {
  def withId(id: Id[UserExperiment]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(state: State[UserExperiment]) = this.copy(state = state)
  def isActive: Boolean = this.state == UserExperimentStates.ACTIVE
}

final case class ExperimentType(value: String)

object ExperimentTypes {
  val ADMIN = State[ExperimentType]("admin")
  val FAKE = State[ExperimentType]("fake")
  val BLOCK = State[ExperimentType]("block")
  val METRO = State[ExperimentType]("metro")
  val NO_SEARCH_EXPERIMENTS = State[ExperimentType]("no search experiments")

  def apply(str: String): State[ExperimentType] = str.toLowerCase.trim match {
    case ADMIN.value => ADMIN
    case BLOCK.value => BLOCK
    case FAKE.value => FAKE
    case METRO.value => METRO
    case NO_SEARCH_EXPERIMENTS.value => NO_SEARCH_EXPERIMENTS
  }
}

object UserExperimentStates extends States[UserExperiment]

case class UserExperimentUserIdKey(userId: Id[User]) extends Key[Seq[State[ExperimentType]]] {
  val namespace = "user_experiment_user_id"
  def toKey(): String = userId.id.toString
}

class UserExperimentCache @Inject()(val repo: FortyTwoCachePlugin)
    extends FortyTwoCache[UserExperimentUserIdKey, Seq[State[ExperimentType]]] {
  private implicit val experimentTypeFormat = State.format[ExperimentType]
  val ttl = 7 days
  def deserialize(obj: Any): Seq[State[ExperimentType]] =
    Json.fromJson[Seq[State[ExperimentType]]](Json.parse(obj.asInstanceOf[String])).get
  def serialize(userExperiments: Seq[State[ExperimentType]]): Any =
    Json.toJson(userExperiments)
}

@ImplementedBy(classOf[UserExperimentRepoImpl])
trait UserExperimentRepo extends Repo[UserExperiment] {
  def getUserExperiments(userId: Id[User])(implicit session: RSession): Seq[State[ExperimentType]]
  def get(userId: Id[User], experiment: State[ExperimentType],
      excludeState: Option[State[UserExperiment]] = Some(UserExperimentStates.INACTIVE))
      (implicit session: RSession): Option[UserExperiment]
  def getByType(experiment: State[ExperimentType])(implicit session: RSession): Seq[UserExperiment]
  def hasExperiment(userId: Id[User], experimentType: State[ExperimentType])(implicit session: RSession): Boolean
}

@Singleton
class UserExperimentRepoImpl @Inject()(
    val db: DataBaseComponent,
    val clock: Clock,
    userExperimentCache: UserExperimentCache
  ) extends DbRepo[UserExperiment] with UserExperimentRepo {

  import DBSession._
  import FortyTwoTypeMappers._
  import db.Driver.Implicit._

  override lazy val table = new RepoTable[UserExperiment](db, "user_experiment") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def experimentType = column[State[ExperimentType]]("experiment_type", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ userId ~ experimentType ~ state <> (UserExperiment,
        UserExperiment.unapply _)
  }

  def getUserExperiments(userId: Id[User])(implicit session: RSession): Seq[State[ExperimentType]] = {
    userExperimentCache.getOrElse(UserExperimentUserIdKey(userId)) {
      (for(f <- table if f.userId === userId && f.state === UserExperimentStates.ACTIVE) yield f.experimentType).list
    }
  }

  def get(userId: Id[User], experimentType: State[ExperimentType],
      excludeState: Option[State[UserExperiment]] = Some(UserExperimentStates.INACTIVE))
      (implicit session: RSession): Option[UserExperiment] = {
    (for {
      f <- table if f.userId === userId && f.experimentType === experimentType && f.state =!= excludeState.orNull
    } yield f).firstOption
  }

  def hasExperiment(userId: Id[User], experimentType: State[ExperimentType])(implicit session: RSession): Boolean = {
    getUserExperiments(userId).contains(experimentType)
  }

  override def invalidateCache(model: UserExperiment)(implicit session: RSession): UserExperiment = {
    userExperimentCache.remove(UserExperimentUserIdKey(model.userId))
    super.invalidateCache(model)
  }

  def getByType(experiment: State[ExperimentType])(implicit session: RSession): Seq[UserExperiment] = {
    val q = for {
      f <- table if f.experimentType === experiment && f.state === UserExperimentStates.ACTIVE
    } yield f
    q.list
  }

}
