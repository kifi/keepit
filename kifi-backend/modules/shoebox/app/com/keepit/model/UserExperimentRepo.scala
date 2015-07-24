package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.slick._
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.{ RSession, RWSession }
import com.keepit.common.time.Clock

@ImplementedBy(classOf[UserExperimentRepoImpl])
trait UserExperimentRepo extends Repo[UserExperiment] with RepoWithDelete[UserExperiment] {
  def getUserExperiments(userId: Id[User])(implicit session: RSession): Set[UserExperimentType]
  def getAllUserExperiments(userId: Id[User])(implicit session: RSession): Seq[UserExperiment]
  def getUserIdsByExperiment(experimentType: UserExperimentType)(implicit session: RSession): Seq[Id[User]]
  def get(userId: Id[User], experiment: UserExperimentType,
    excludeState: Option[State[UserExperiment]] = Some(UserExperimentStates.INACTIVE))(implicit session: RSession): Option[UserExperiment]
  def getByType(experiment: UserExperimentType)(implicit session: RSession): Seq[UserExperiment]
  def hasExperiment(userId: Id[User], experimentType: UserExperimentType)(implicit session: RSession): Boolean
  def getDistinctExperimentsWithCounts()(implicit session: RSession): Seq[(UserExperimentType, Int)]
}

@Singleton
class UserExperimentRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock,
  val userRepo: UserRepo,
  userExperimentCache: UserExperimentCache,
  allFakeUsersCache: AllFakeUsersCache)
    extends DbRepo[UserExperiment] with DbRepoWithDelete[UserExperiment] with UserExperimentRepo {

  import db.Driver.simple._

  type RepoImpl = UserExperimentTable
  class UserExperimentTable(tag: Tag) extends RepoTable[UserExperiment](db, tag, "user_experiment") {
    def userId = column[Id[User]]("user_id", O.NotNull)
    def experimentType = column[UserExperimentType]("experiment_type", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, experimentType, state) <> ((UserExperiment.apply _).tupled, UserExperiment.unapply _)
  }

  def table(tag: Tag) = new UserExperimentTable(tag)
  initTable()

  override def save(model: UserExperiment)(implicit session: RWSession): UserExperiment = {
    val saved = super.save(model)
    userRepo.save(userRepo.get(model.userId)) // just to bump up user seqNum
    saved
  }

  def getUserExperiments(userId: Id[User])(implicit session: RSession): Set[UserExperimentType] = {
    userExperimentCache.getOrElse(UserExperimentUserIdKey(userId)) {
      (for (f <- rows if f.userId === userId && f.state === UserExperimentStates.ACTIVE) yield f.experimentType).list
    } toSet
  }

  def getAllUserExperiments(userId: Id[User])(implicit session: RSession): Seq[UserExperiment] = {
    (for (f <- rows if f.userId === userId) yield f).list
  }

  def getUserIdsByExperiment(experimentType: UserExperimentType)(implicit session: RSession) =
    (for (f <- rows if f.experimentType === experimentType && f.state === UserExperimentStates.ACTIVE) yield f.userId).list

  def get(userId: Id[User], experimentType: UserExperimentType,
    excludeState: Option[State[UserExperiment]] = Some(UserExperimentStates.INACTIVE))(implicit session: RSession): Option[UserExperiment] = {
    (for {
      f <- rows if f.userId === userId && f.experimentType === experimentType && f.state =!= excludeState.orNull
    } yield f).firstOption
  }

  def hasExperiment(userId: Id[User], experimentType: UserExperimentType)(implicit session: RSession): Boolean = {
    getUserExperiments(userId).contains(experimentType)
  }

  override def invalidateCache(model: UserExperiment)(implicit session: RSession): Unit = {
    userExperimentCache.remove(UserExperimentUserIdKey(model.userId))
    if (model.experimentType == UserExperimentType.FAKE) { allFakeUsersCache.remove(AllFakeUsersKey) }
  }

  override def deleteCache(model: UserExperiment)(implicit session: RSession): Unit = {
    userExperimentCache.remove(UserExperimentUserIdKey(model.userId))
    if (model.experimentType == UserExperimentType.FAKE) { allFakeUsersCache.remove(AllFakeUsersKey) }
  }

  def getByType(experiment: UserExperimentType)(implicit session: RSession): Seq[UserExperiment] = {
    val q = for {
      f <- rows if f.experimentType === experiment && f.state === UserExperimentStates.ACTIVE
    } yield f
    q.list
  }

  def getDistinctExperimentsWithCounts()(implicit session: RSession): Seq[(UserExperimentType, Int)] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val query = sql"SELECT experiment_type, COUNT(*) FROM user_experiment WHERE state='active' GROUP BY experiment_type;"
    query.as[(String, Int)].list.map {
      case (name, count) =>
        (UserExperimentType(name), count)
    }
  }
}
