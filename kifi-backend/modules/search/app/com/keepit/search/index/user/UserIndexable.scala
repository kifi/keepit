package com.keepit.search.index.user

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream }

import com.keepit.common.db.{ State, ExternalId, SequenceNumber, Id }
import com.keepit.common.mail.EmailAddress
import com.keepit.model.UserStates._
import com.keepit.model.{ Username, ExperimentType, User }
import com.keepit.search.index.{ FieldDecoder, DefaultAnalyzer, Indexable }
import com.keepit.social.BasicUser
import com.keepit.typeahead.PrefixFilter
import org.apache.lucene.store.{ InputStreamDataInput, OutputStreamDataOutput }

object UserFields {
  val PREFIX_MAX_LEN = 8 // do not change this number unless you do reindexing immediately
  val nameField = "t"
  val nameStemmedField = "ts"
  val namePrefixField = "tp"
  val emailsField = "e"
  val recordField = "rec"
  val experimentsField = "exp"

  val nameSearchFields = Set(nameField)

  val decoders: Map[String, FieldDecoder] = Map.empty
}

object UserIndexable {
  val toBeDeletedStates = Set[State[User]](INACTIVE, PENDING, BLOCKED, INCOMPLETE_SIGNUP)
}

class UserIndexable(user: User, emails: Set[EmailAddress], experiments: Set[ExperimentType]) extends Indexable[User, User] {

  import UserFields._

  val id: Id[User] = user.id.get
  val sequenceNumber: SequenceNumber[User] = user.seq
  val isDeleted: Boolean = UserIndexable.toBeDeletedStates.contains(user.state)

  private def genPrefixes(user: User): Set[String] = {
    val tokens = PrefixFilter.tokenize(user.fullName).map { _.take(PREFIX_MAX_LEN) }
    val prefixes = tokens.flatMap { token => (0 until token.length).map { i => token.slice(0, i + 1) } }
    prefixes.toSet
  }

  override def buildDocument = {
    val doc = super.buildDocument

    doc.add(buildTextField(nameField, user.fullName, DefaultAnalyzer.defaultAnalyzer))

    doc.add(buildTextField(nameStemmedField, user.fullName, DefaultAnalyzer.defaultAnalyserWithStemmer))

    doc.add(buildIteratorField[String](namePrefixField, genPrefixes(user).toIterator)(x => x))

    doc.add(buildIteratorField[String](emailsField, emails.map { _.address.toLowerCase }.toIterator)(x => x))

    doc.add(buildIteratorField[String](experimentsField, experiments.map { _.value }.toIterator)(x => x))

    doc.add(buildBinaryDocValuesField(recordField, BasicUserSerializer.toByteArray(BasicUser.fromUser(user))))

    doc
  }
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
          username = Username("")
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
          }
        )
      case _ =>
        throw new Exception(s"invalid data [version=${version}]")
    }
  }
}