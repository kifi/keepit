package com.keepit.common.db

import java.io.BufferedReader
import java.sql.{Clob, Connection, PreparedStatement, ResultSet, Timestamp, SQLException}
import java.util.UUID
import scala.util.control.ControlThrowable
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import play.api.db._
import play.api.db._
import play.api.mvc.PathBindable
import play.api.mvc.QueryStringBindable
import play.api._
import ru.circumflex.core._
import ru.circumflex.orm._
import play.api.libs.concurrent.Akka
import akka.util.duration._
import com.keepit.common.healthcheck.Healthcheck
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.inject._
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.{Babysitter, BabysitterTimeout}

class CustomTypeConverter extends TypeConverter {
  override def write(st: PreparedStatement, parameter: Any, paramIndex: Int) {
    parameter match {
      case Some(value) => write(st, value, paramIndex)
      case Id(value) => st.setLong(paramIndex, value)
      case ExternalId(value) => st.setObject(paramIndex, value)
      case State(value) => st.setObject(paramIndex, value)
      case dt: DateTime => super.write(st, dt.toDate, paramIndex)
      case _ => super.write(st, parameter, paramIndex)
    }
  }
}

case class Id[T](id: Long) {
  override def toString = id.toString
}

object Id {
  implicit def queryStringBinder[T](implicit longBinder: QueryStringBindable[Long]) = new QueryStringBindable[Id[T]] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, Id[T]]] = {
      longBinder.bind(key, params) map {
        case Right(id) => Right(Id(id))
        case _ => Left("Unable to bind an Id")
      }
    }
    override def unbind(key: String, id: Id[T]): String = {
      longBinder.unbind(key, id.id)
    }
  }

  implicit def pathBinder[T](implicit longBinder: PathBindable[Long]) = new PathBindable[Id[T]] {
    override def bind(key: String, value: String): Either[String, Id[T]] =
      longBinder.bind(key, value) match {
        case Right(id) => Right(Id(id))
        case _ => Left("Unable to bind an Id")
      }
    override def unbind(key: String, id: Id[T]): String = id.id.toString
  }
}

class DbException(message: String, error: Throwable) extends RuntimeException(message, error)

  /** Field to store a type-safe integer database identifier.
 * T is the referenced type
 * R is the record type of which this field is a member
 */
class IdField[T, R <: Record[_, R]](name: String, record: R)
    extends XmlSerializable[Id[T], R](name, record, ormConf.dialect.longType)
    with AutoIncrementable[Id[T], R] {

  def fromString(str: String): Option[Id[T]] =
    try Some(Id(str.toLong)) catch { case e: Exception => None }

  override def toString(value: Option[Id[T]]): String =
    value.map(_.id.toString).getOrElse("")

  override def read(rs: ResultSet, alias: String): Option[Id[T]] = {
    val o = rs.getObject(alias)
    if (rs.wasNull) None
    else Some(Id(o.asInstanceOf[Long]))
  }
}


/** Converts strings to IdFields.  Useful for implicit conversion via the IdFields mixin. */
class IdDefinitionHelper[R <: Record[_, R]](name: String, record: R) {
  def ID[T] = new IdField[T, R](name, record)
}

/** Mixin to add implicit conversion from String to IdField via ID method */
trait IdFields[R <: Record[_, R]] { self: R =>
  implicit def str2IdHelper(str: String): IdDefinitionHelper[R] =
    new IdDefinitionHelper(str, this)
}


case class ExternalId[T](id: String) {
  if (!ExternalId.UUID_PATTERN.pattern.matcher(id).matches()) {
    throw new Exception("external id [%s] does not match uuid pattern".format(id))
  }
  override def toString = id
}

object ExternalId {

  val UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$".r

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[ExternalId[T]] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, ExternalId[T]]] = {
      stringBinder.bind(key, params) map {
        case Right(id) => Right(ExternalId(id))
        case _ => Left("Unable to bind an ExternalId")
      }
    }
    override def unbind(key: String, id: ExternalId[T]): String = {
      stringBinder.unbind(key, id.id)
    }
  }

  implicit def pathBinder[T] = new PathBindable[ExternalId[T]] {
    override def bind(key: String, value: String): Either[String, ExternalId[T]] =
      Right(ExternalId(value)) // TODO: handle errors if value is malformed

    override def unbind(key: String, id: ExternalId[T]): String = id.toString
  }

  def apply[T](): ExternalId[T] = ExternalId(UUID.randomUUID.toString)
}


/** Field to store a type-safe string database identifier.
 * T is the referenced type
 * R is the record type of which this field is a member
 */
class ExternalIdField[T, R <: Record[_, R]](name: String, record: R)
    extends XmlSerializable[ExternalId[T], R](name, record, ormConf.dialect.varcharType(36)) {

  def fromString(str: String): Option[ExternalId[T]] =
    if (str.isEmpty) None else Some(ExternalId(str))

  override def toString(value: Option[ExternalId[T]]): String =
    value.map(_.id).getOrElse("")

  override def read(rs: ResultSet, alias: String): Option[ExternalId[T]] = {
    val o = rs.getObject(alias)
    if (rs.wasNull) None
    else Some(ExternalId(o.asInstanceOf[String]))
  }
}

/** Converts strings to ExternalIdFields.  Useful for implicit conversion via the ExternalIdFields mixin. */
class ExternalIdDefinitionHelper[R <: Record[_, R]](name: String, record: R) {
  def EXTERNAL_ID[T] = new ExternalIdField[T, R](name, record)
}

/** Mixin to add implicit conversion from String to IdField via ID method */
trait ExternalIdFields[R <: Record[_, R]] { self: R =>
  implicit def str2ExternalIdHelper(str: String): ExternalIdDefinitionHelper[R] =
    new ExternalIdDefinitionHelper(str, this)
}


case class State[T](val value: String) {
  override def toString = value
}

class StateException(message: String) extends Exception(message)

object State {

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[State[T]] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, State[T]]] = {
      stringBinder.bind(key, params) map {
        case Right(value) => Right(State(value))
        case _ => Left("Unable to bind a State")
      }
    }
    override def unbind(key: String, state: State[T]): String = {
      stringBinder.unbind(key, state.value)
    }
  }

  implicit def pathBinder[T] = new PathBindable[State[T]] {
    override def bind(key: String, value: String): Either[String, State[T]] =
      Right(State(value)) // TODO: handle errors if value is malformed

    override def unbind(key: String, state: State[T]): String = state.toString
  }
}

/** Field to store a type-safe string state.
 * R is the record type of which this field is a member
 */
class StateField[T, R <: Record[_, R]](name: String, record: R, length: Int)
    extends XmlSerializable[State[T], R](name, record, ormConf.dialect.varcharType(length)) {

  def fromString(str: String): Option[State[T]] =
    if (str.isEmpty) None else Some(State(str))

  override def toString(value: Option[State[T]]): String =
    value.map(_.value).getOrElse("")

  override def read(rs: ResultSet, alias: String): Option[State[T]] = {
    val o = rs.getObject(alias)
    if (rs.wasNull) None
    else Some(State(o.asInstanceOf[String]))
  }
}

/** Converts strings to ExternalIdFields.  Useful for implicit conversion via the ExternalIdFields mixin. */
class StateDefinitionHelper[R <: Record[_, R]](name: String, record: R) {
  def STATE[T] = new StateField[T, R](name, record, 20)
  def STATE[T](length: Int) = new StateField[T, R](name, record, length)
}

/** Mixin to add implicit conversion from String to IdField via ID method */
trait StateFields[R <: Record[_, R]] { self: R =>
  implicit def str2StateHelper(str: String): StateDefinitionHelper[R] =
    new StateDefinitionHelper(str, this)
}

// field types that use java.sql.Clob
class ClobField[R <: Record[_, R]](name: String, record: R)
    extends XmlSerializable[String, R](name, record, ormConf.dialect.textType) {

  def fromString(str: String): Option[String] = Some(str)
  override def toString(value: Option[String]): String = value.get

  override def read(rs: ResultSet, alias: String): Option[String] = {
    val o = rs.getObject(alias)
    if (rs.wasNull) {
      None
    } else {
      Some(resultToString(o))
    }
  }

  //depends on the driver, h2 returns a clob and mysql returns a string.
  private def resultToString(result: Any): String = if(result.getClass().isAssignableFrom(classOf[String])) {
    result.asInstanceOf[String]
  } else {
    clobToString(result.asInstanceOf[Clob])
  }

  private def clobToString(result: Clob): String = {
    val clob: Clob = try {
      result.asInstanceOf[Clob]
    } catch {
      //ControlThrowable is used for control flow of scala's closure management. You must throw it up!
      case e: ControlThrowable => throw e
      case e => throw new DbException("error converting instance %s to clob with for name %s. cause: %s\n%s".format(result.toString(), name, e.toString(), e.getStackTrace() mkString "\n"), e)
    }
    val reader = new BufferedReader(clob.getCharacterStream())
    val builder = new StringBuilder()
    var aux = reader.readLine()
    while (aux != null) {
      builder.append(aux)
      aux = reader.readLine()
    }
    builder.toString()
  }
}

/** Converts strings to ClobFields.  Useful for implicit conversion via the ClobFields mixin. */
class ClobDefinitionHelper[R <: Record[_, R]](name: String, record: R) {
  def CLOB = new ClobField[R](name, record)
}

/** Mixin to add implicit conversion from String to ClobField via CLOB method */
trait ClobFields[R <: Record[_, R]] { self: R =>
  implicit def str2ClobHelper(str: String): ClobDefinitionHelper[R] =
    new ClobDefinitionHelper(str, this)
}

// field types that use org.joda.time.DateTime instead of java.util.Date
class JodaTimestampField[R <: Record[_, R]](name: String, record: R)
    extends XmlSerializable[DateTime, R](name, record, ormConf.dialect.timestampType) {
  def fromString(str: String): Option[DateTime] =
    try Some(Timestamp.valueOf(str).toDateTime) catch { case e: Exception => None }
  override def toString(value: Option[DateTime]): String =
    value.map(v => new Timestamp(v.toDate.getTime).toString).getOrElse("")

  override def read(rs: ResultSet, alias: String): Option[DateTime] = {
    val o = rs.getObject(alias)
    if (rs.wasNull) {
      None
    }
    else {
      val timestemp: Timestamp = try {
        o.asInstanceOf[Timestamp]
      } catch {
        //ControlThrowable is used for control flow of scala's closure management. You must throw it up!
        case e: ControlThrowable => throw e
        case e => throw new DbException("error converting instance %s to timestemp with alias %s for name %s. cause: %s\n%s".format(o.toString(), alias, name, e.toString(), e.getStackTrace() mkString "\n"), e)
      }
      Some(timestemp.toDateTime)
    }
  }
}

class JodaDateField[R <: Record[_, R]](name: String, record: R)
    extends XmlSerializable[DateTime, R](name, record, ormConf.dialect.dateType) {
  def fromString(str: String): Option[DateTime] =
    try Some(java.sql.Date.valueOf(str).toDateTime) catch { case e: Exception => None }
  override def toString(value: Option[DateTime]): String =
    value.map(v => new java.sql.Date(v.toDate.getTime).toString).getOrElse("")

  override def read(rs: ResultSet, alias: String): Option[DateTime] = {
    val o = rs.getObject(alias)
    if (rs.wasNull) None
    else Some(o.asInstanceOf[java.sql.Date].toDateTime)
  }
}

class JodaTimeField[R <: Record[_, R]](name: String, record: R)
    extends XmlSerializable[DateTime, R](name, record, ormConf.dialect.timeType) {
  def fromString(str: String): Option[DateTime] =
    try Some(java.sql.Time.valueOf(str).toDateTime) catch { case e: Exception => None }
  override def toString(value: Option[DateTime]): String =
    value.map(v => new java.sql.Time(v.toDate.getTime).toString).getOrElse("")

  override def read(rs: ResultSet, alias: String): Option[DateTime] = {
    val o = rs.getObject(alias)
    if (rs.wasNull) None
    else Some(o.asInstanceOf[java.sql.Time].toDateTime)
  }
}

/** Converts strings to IdFields.  Useful for implicit conversion via the IdFields mixin. */
class JodaDefinitionHelper[R <: Record[_, R]](name: String, record: R) {
  def JODA_TIME = new JodaTimeField(name, record)
  def JODA_DATE = new JodaDateField(name, record)
  def JODA_TIMESTAMP = new JodaTimestampField(name, record)
}

/** Mixin to add implicit conversion from String to IdField via ID method */
trait JodaFields[R <: Record[_, R]] { self: R =>
  implicit def str2JodaHelper(str: String): JodaDefinitionHelper[R] =
    new JodaDefinitionHelper(str, this)
}


trait TypedIdentityGenerator[T, R <: Record[Id[T], R]] extends IdentityGenerator[Id[T], R] { this: R =>
  override def persist(fields: scala.Seq[Field[_, R]]): Int = {
    // Make sure that PRIMARY_KEY contains `NULL`
    this.PRIMARY_KEY.setNull()
    // Persist all not-null fields
    val result = new Insert(relation, fields.filter(!_.isEmpty)).execute()
    // Fetch either the whole record or just an identifier.
    val root = relation.AS("root")
    if (relation.isAutoRefresh)
      SELECT(root.*).FROM(root).WHERE(ormConf.dialect.identityLastIdPredicate(root)).unique() match {
        case Some(r) => relation.copyFields(r, this) // TODO: not typechecked at runtime
        case _ => throw new ORMException("Backend didn't return last inserted record. " +
            "Try another identifier generation strategy.")
      }
    else ormConf.dialect.identityLastIdQuery(root).unique() match {
      case Some(id) => this.PRIMARY_KEY := Id(id.asInstanceOf[Long])
      case _ => throw new ORMException("Backend didn't return last generated identity. " +
          "Try another identifier generation strategy.")
    }
    result
  }
}

abstract class Entity[T, R <: Record[Id[T], R]]
  extends Record[Id[T], R]
  with IdFields[R]
  with ExternalIdFields[R]
  with TypedIdentityGenerator[T, R]
  with StateFields[R]
  with JodaFields[R]
  with ClobFields[R]
{
  this: R =>

  val id = "id".ID[T].NOT_NULL.AUTO_INCREMENT

  val PRIMARY_KEY = id

  //def view(implicit conn: Connection): T
}

trait EntityTable[T, R <: Record[Id[T], R]] extends Table[Id[T], R] { this: R =>
  //def apply(view: T): R
}

trait FortyTwoDialect {
  def DATEDIFF(ex1: String, ex2: String) : String
}

object CX extends Logging {

  //The following makes sure the underlying ORMConfiguration won't burf on us when its looking for these params.
  ru.circumflex.core.cx("orm.connection.url") = ""
  ru.circumflex.core.cx("orm.connection.username") = ""
  ru.circumflex.core.cx("orm.connection.password") = ""

  class BasicConnectionProvider(name: String, readOnly: Boolean)
                               (implicit app: Application) extends ConnectionProvider {
    private var _connection: Option[Connection] = None
    def openConnection: Connection = {
      if (_connection.isEmpty) {
        val connection = getConnection(name = name, trials = 3)
        connection.setReadOnly(readOnly)
        _connection = Some(connection)
      }
      _connection.get
    }
    def close: Unit = {
      _connection.map(_.close())
      _connection = None
    }
  }

  private class BasicOrmConf(readOnly: Boolean = false)(implicit app: Application) extends Logging with ORMConfiguration {

    override val url = {
      val connection = getConnection(name = "shoebox", trials = 3)
      val url = connection.getMetaData.getURL
      connection.close()
      url
    }

    private def getConnection(name: String, trials: Int): Connection =
      try {
        return DB.getConnection(name, false)
      } catch {
        case e: SQLException =>
          if (trials > 0) {
            log.error("Exception while trying to get connection. %s trials to go".format(trials), e)
            getConnection(name, trials - 1)
          }
          else throw e
      }

    override val name = "shoebox"
    override lazy val connectionProvider = new BasicConnectionProvider(name, readOnly)
    override lazy val typeConverter = new CustomTypeConverter

    override lazy val dialect = cx.instantiate[Dialect]("orm.dialect", url match {
      case u if (u.startsWith("jdbc:mysql:")) => new MySQLDialect() with FortyTwoDialect {
        override def ILIKE(ex1: String, ex2: String = "?") = "%s LIKE %s".format(ex1, ex2)
        def DATEDIFF(ex1: String, ex2: String) = "DATEDIFF(%s, %s)".format(ex1, ex2)
      }
      case u if (u.startsWith("jdbc:h2:")) => new H2Dialect() with FortyTwoDialect {
        override def ILIKE(ex1: String, ex2: String = "?") = "LOWER(%s) LIKE %s".format(ex1, ex2)
        def DATEDIFF(ex1: String, ex2: String) = "DATEDIFF('DAY', %s, %s)".format(ex1, ex2)
      }
      case _ => throw new Exception("unknown dialect " + url)
    })
  }

  def withReadOnlyConnection[A](block: Connection => A)(implicit timeout: BabysitterTimeout = BabysitterTimeout(1 second, 5 seconds), app: Application): A =
    executeBlockWithConnection(true, block)

  def withConnection[A](block: Connection => A)(implicit timeout: BabysitterTimeout = BabysitterTimeout(1 second, 5 seconds), app: Application): A =
    executeBlockWithConnection(false, block)

  private def executeBlockWithConnection[A](readOnly: Boolean, block: Connection => A)(implicit timeout: BabysitterTimeout, app: Application): A = {
    val conf = new BasicOrmConf(readOnly = readOnly)
    val connection: Connection = conf.connectionProvider.openConnection
    try {
      inject[Babysitter].watch(timeout) {
        using(conf) {
          block(connection)
        }
      }
    } catch {
      //ControlThrowable is used for control flow of scala's closure management. You must throw it up!
      case e: ControlThrowable => throw e
      case e =>
        log.error(">>Exception while executing block with readonly = %s".format(readOnly), e)
        e.printStackTrace()
        throw new DbException("Exception while executing block with readonly = %s. cause: %s\n%s".format(
            readOnly, e.toString(), e.getStackTrace() mkString "\n"), e)
    } finally {
      connection.close()
    }
  }
}
