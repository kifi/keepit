package com.keepit.search.user

import com.keepit.common.db.{ ExternalId, Id, SequenceNumber, State }
import com.keepit.model.{ Username, User, ExperimentType }
import com.keepit.social.BasicUser
import com.keepit.search.index.{ IndexDirectory, Indexable, Indexer, DefaultAnalyzer }
import org.apache.lucene.store.{ InputStreamDataInput, OutputStreamDataOutput }
import org.apache.lucene.util.Version
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
import com.keepit.model.UserStates._
import com.keepit.search.IndexInfo
import com.keepit.typeahead.PrefixFilter
import com.keepit.common.mail.EmailAddress
import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }

object UserIndexer {
  val luceneVersion = Version.LUCENE_47

  val FULLNAME_FIELD = "u_fullname"
  val EMAILS_FIELD = "u_emails"
  val BASIC_USER_FIELD = "u_basic_user"
  val USER_EXPERIMENTS = "u_experiments"
  val PREFIX_FIELD = "u_prefix"
  val PREFIX_MAX_LEN = 8 // do not change this number unless you do reindexing immediately

  val toBeDeletedStates = Set[State[User]](INACTIVE, PENDING, BLOCKED, INCOMPLETE_SIGNUP)
}

object BasicUserSerializer {
  def toByteArray(basicUser: BasicUser): Array[Byte] = {
    val bos = new ByteArrayOutputStream()
    val oos = new OutputStreamDataOutput(bos)
    oos.writeByte(2) // version
    oos.writeString(basicUser.externalId.toString)
    oos.writeString(basicUser.firstName)
    oos.writeString(basicUser.lastName)
    oos.writeString(basicUser.pictureName)
    oos.writeString(basicUser.username.value)
    oos.close()
    bos.close()
    bos.toByteArray()
  }

  def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): BasicUser = {
    val in = new InputStreamDataInput(new ByteArrayInputStream(bytes, offset, length))

    val version = in.readByte().toInt

    version match {
      case 1 => // pre-username
        BasicUser(
          externalId = ExternalId[User](in.readString),
          firstName = in.readString,
          lastName = in.readString,
          pictureName = in.readString,
          username = Username(""),
          active = true
        )
      case 2 => // with username
        BasicUser(
          externalId = ExternalId[User](in.readString),
          firstName = in.readString,
          lastName = in.readString,
          pictureName = in.readString,
          username = {
            val u = in.readString
            Username(u)
          },
          active = true
        )
      case _ =>
        throw new Exception(s"invalid data [version=${version}]")
    }
  }
}

class UserIndexer(
    indexDirectory: IndexDirectory,
    override val airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient) extends Indexer[User, User, UserIndexer](indexDirectory) {

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
        info.toIterator.map { x => buildIndexable(x.user, x.basicUser, x.emails, x.experiments) }
      }
    }
    total
  }

  override def indexInfos(name: String): Seq[IndexInfo] = {
    super.indexInfos("UserIndex" + name)
  }

  case class UserInfo(user: User, basicUser: BasicUser, emails: Seq[EmailAddress], experiments: Seq[ExperimentType])

  private def getUsersInfo(fetchSize: Int): Seq[UserInfo] = {
    val usersFuture = shoeboxClient.getUserIndexable(sequenceNumber, fetchSize)

    val infoFuture = usersFuture.flatMap { users =>
      if (users.isEmpty) Future.successful(Seq[UserInfo]())
      else {
        val userIds = users.map(_.id.get)
        val emailsFuture = shoeboxClient.getEmailAddressesForUsers(userIds)
        val experimentsFuture = shoeboxClient.getExperimentsByUserIds(userIds)

        val infoFuture = for {
          emails <- emailsFuture
          experiments <- experimentsFuture
        } yield {
          users.flatMap { user =>
            val id = user.id.get
            (BasicUser.fromUser(user), emails.get(id), experiments.get(id)) match {
              case (basicUser, Some(emails), Some(exps)) => Some(UserInfo(user, basicUser, emails, exps.toSeq))
              case _ => None
            }
          }
        }
        infoFuture
      }
    }
    Await.result(infoFuture, 5 seconds)
  }

  def buildIndexable(user: User, basicUser: BasicUser, emails: Seq[EmailAddress], experiments: Seq[ExperimentType]): UserIndexable = {
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
      override val sequenceNumber: SequenceNumber[User],
      override val isDeleted: Boolean,
      val user: User,
      val basicUser: BasicUser,
      val emails: Seq[EmailAddress],
      val experiments: Seq[ExperimentType]) extends Indexable[User, User] {

    private def genPrefix(user: User): Set[String] = {
      val tokens = PrefixFilter.tokenize(user.firstName + " " + user.lastName).map { _.take(PREFIX_MAX_LEN) }
      val prefixes = tokens.flatMap { token => (0 until token.length).map { i => token.slice(0, i + 1) } }
      prefixes.toSet
    }

    override def buildDocument = {
      val doc = super.buildDocument

      val userNameField = buildTextField(FULLNAME_FIELD, user.firstName + " " + user.lastName, analyzer)
      doc.add(userNameField)

      val emailField = buildIteratorField[String](EMAILS_FIELD, emails.map { _.address.toLowerCase }.toIterator)(x => x)
      doc.add(emailField)

      val expField = buildIteratorField[String](USER_EXPERIMENTS, experiments.map { _.value }.toIterator)(x => x)
      doc.add(expField)

      val basicUserField = buildBinaryDocValuesField(BASIC_USER_FIELD, BasicUserSerializer.toByteArray(basicUser))
      doc.add(basicUserField)

      val prefixField = buildIteratorField[String](PREFIX_FIELD, genPrefix(user).toIterator)(x => x)
      doc.add(prefixField)

      doc
    }
  }
}

