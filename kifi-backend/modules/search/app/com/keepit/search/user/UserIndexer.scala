package com.keepit.search.user

import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.model.User
import com.keepit.social.BasicUser
import com.keepit.search.index.{IndexDirectory, Indexable, Indexer, DefaultAnalyzer}
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
import com.keepit.common.db.State
import com.keepit.model.UserStates._
import com.keepit.model.ExperimentType
import com.keepit.search.IndexInfo


object UserIndexer {
  val luceneVersion = Version.LUCENE_41

  val FULLNAME_FIELD = "u_fullname"
  val EMAILS_FIELD = "u_emails"
  val BASIC_USER_FIELD = "u_basic_user"
  val USER_EXPERIMENTS = "u_experiments"

  val toBeDeletedStates = Set[State[User]](INACTIVE, PENDING, BLOCKED, INCOMPLETE_SIGNUP)
}

class UserIndexer(
  indexDirectory: IndexDirectory,
  writerConfig: IndexWriterConfig,
  airbrake: AirbrakeNotifier,
  shoeboxClient: ShoeboxServiceClient
  ) extends Indexer[User](indexDirectory, writerConfig) {

  import UserIndexer._

  override val commitBatchSize = 50
  private val fetchSize = 250

  def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done) {
      total += doUpdate("UserIndex") {
        val info = getUsersInfo(fetchSize)
        log.info(s"${info.size} users to be indexed")
        done = info.isEmpty
        info.toIterator.map{ x => buildIndexable(x.user, x.basicUser, x.emails, x.experiments) }
      }
    }
    total
  }

  override def indexInfos(name: String): Seq[IndexInfo] = {
    super.indexInfos("UserIndex" + name)
  }

  case class UserInfo(user: User, basicUser: BasicUser, emails: Seq[String], experiments: Seq[ExperimentType])

  private def getUsersInfo(fetchSize: Int): Seq[UserInfo] = {
    val usersFuture = shoeboxClient.getUserIndexable(sequenceNumber.value, fetchSize)

    val infoFuture = usersFuture.flatMap{ users =>
      if (users.isEmpty) Future.successful(Seq[UserInfo]())
      else {
        val userIds = users.map(_.id.get)
        val basicUsersFuture = shoeboxClient.getBasicUsers(userIds)
        val emailsFuture = shoeboxClient.getEmailAddressesForUsers(userIds)
        val experimentsFuture = shoeboxClient.getExperimentsByUserIds(userIds)

        val infoFuture = for {
          basicUsers <- basicUsersFuture
          emails <- emailsFuture
          experiments <- experimentsFuture
        } yield {
          users.flatMap{ user =>
            val id = user.id.get
            (basicUsers.get(id), emails.get(id), experiments.get(id)) match {
              case (Some(basicUser), Some(emails), Some(exps)) => Some(UserInfo(user, basicUser, emails, exps.toSeq))
              case _ => None
            }
          }
        }
        infoFuture
      }
    }
    Await.result(infoFuture, 5 seconds)
  }

  def buildIndexable(user: User, basicUser: BasicUser, emails: Seq[String], experiments: Seq[ExperimentType]): UserIndexable = {
    new UserIndexable(
      id = user.id.get,
      sequenceNumber = user.seq,
      isDeleted = toBeDeletedStates.contains(user.state),
      user = user,
      basicUser = basicUser,
      emails = emails,
      experiments = experiments
    )
  }

  val analyzer = DefaultAnalyzer.defaultAnalyzer

  class UserIndexable(
    override val id: Id[User],
    override val sequenceNumber: SequenceNumber,
    override val isDeleted: Boolean,
    val user: User,
    val basicUser: BasicUser,
    val emails: Seq[String],
    val experiments: Seq[ExperimentType]) extends Indexable[User] {

    override def buildDocument = {
      val doc = super.buildDocument

      val userNameField = buildTextField(FULLNAME_FIELD, user.firstName + " " + user.lastName, analyzer)
      doc.add(userNameField)

      val emailField = buildIteratorField[String](EMAILS_FIELD, emails.map{_.toLowerCase}.toIterator)(x => x)
      doc.add(emailField)

      val expField = buildIteratorField[String](USER_EXPERIMENTS, experiments.map{_.value}.toIterator)(x => x)
      doc.add(expField)

      val basicUserField = buildBinaryDocValuesField(BASIC_USER_FIELD, BasicUser.toByteArray(basicUser))
      doc.add(basicUserField)

      doc
    }
  }
}


